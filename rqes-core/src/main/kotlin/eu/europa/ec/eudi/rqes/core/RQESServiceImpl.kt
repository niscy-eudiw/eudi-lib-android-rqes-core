/*
 * Copyright (c) 2024 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.europa.ec.eudi.rqes.core

import eu.europa.ec.eudi.rqes.AuthorizationCode
import eu.europa.ec.eudi.rqes.CSCClient
import eu.europa.ec.eudi.rqes.CSCClientConfig
import eu.europa.ec.eudi.rqes.CredentialAuthorizationRequestPrepared
import eu.europa.ec.eudi.rqes.CredentialAuthorizationSubject
import eu.europa.ec.eudi.rqes.CredentialAuthorized
import eu.europa.ec.eudi.rqes.CredentialInfo
import eu.europa.ec.eudi.rqes.CredentialRef
import eu.europa.ec.eudi.rqes.CredentialsListRequest
import eu.europa.ec.eudi.rqes.DefaultHttpClientFactory
import eu.europa.ec.eudi.rqes.DocumentDigestList
import eu.europa.ec.eudi.rqes.DocumentToSign
import eu.europa.ec.eudi.rqes.HttpsUrl
import eu.europa.ec.eudi.rqes.RSSPMetadata
import eu.europa.ec.eudi.rqes.ServiceAccessAuthorized
import eu.europa.ec.eudi.rqes.ServiceAuthorizationRequestPrepared
import eu.europa.ec.eudi.rqes.SigningAlgorithmOID
import io.ktor.client.HttpClient
import org.jetbrains.annotations.VisibleForTesting
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The RQES service implementation.
 * This class provides the implementation of the RQES service.
 *
 * @property serviceEndpointUrl The RQES service endpoint URL.
 * @property config The RQES service configuration.
 * @property clientFactory The HTTP client factory. If this property is null, the default HTTP client factory will be used.
 *
 * @constructor Creates a RQES service implementation.
 * @param serviceEndpointUrl The RQES service endpoint URL.
 * @param config The RQES service configuration.
 * @param clientFactory The HTTP client factory. If this property is null, the default HTTP client factory will be used.
 */
class RQESServiceImpl(
    @VisibleForTesting internal val serviceEndpointUrl: String,
    @VisibleForTesting internal val config: CSCClientConfig,
    @VisibleForTesting internal val clientFactory: (() -> HttpClient)? = null
) : RQESService {

    /**
     * The server state. This property is used to store the server state.
     */
    internal lateinit var serverState: String

    /**
     * The service authorization request prepared. This property is used to store the service authorization request prepared.
     */
    internal var serviceAuthRequestPrepared: ServiceAuthorizationRequestPrepared? = null

    /**
     * The client. This property is used to store the client.
     */
    @VisibleForTesting
    internal lateinit var client: CSCClient

    /**
     * Get or create the client.
     * This method is used to get or create the client.
     * @return The client.
     */
    @Throws(Throwable::class)
    @VisibleForTesting
    internal suspend fun getOrCreateClient(): CSCClient {
        if (::client.isInitialized.not()) {
            client = CSCClient.oauth2(
                config,
                serviceEndpointUrl,
                clientFactory ?: DefaultHttpClientFactory
            ).getOrThrow()
        }
        return client
    }

    override suspend fun getRSSPMetadata(): Result<RSSPMetadata> {
        return try {
            Result.success(getOrCreateClient().rsspMetadata)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun getServiceAuthorizationUrl(): Result<HttpsUrl> {
        serverState = Uuid.random().toString()
        return try {
            with(getOrCreateClient()) {
                val authorizationCodeURL = prepareServiceAuthorizationRequest().getOrThrow()
                    .also { serviceAuthRequestPrepared = it }
                    .value.authorizationCodeURL
                Result.success(authorizationCodeURL)
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun authorizeService(authorizationCode: AuthorizationCode): Result<RQESService.Authorized> {
        return try {
            check(::serverState.isInitialized) {
                "Server state not initialized. Call getServiceAuthorizationUrl() first"
            }
            serviceAuthRequestPrepared?.let { prepared ->
                with(getOrCreateClient()) {
                    val authorized =
                        prepared.authorizeWithAuthorizationCode(authorizationCode, serverState)
                            .getOrThrow()
                    Result.success(
                        AuthorizedImpl(
                            serverState = serverState,
                            client = this@with,
                            serviceAccessAuthorized = authorized,
                        )
                    )
                }
            }
                ?: throw IllegalStateException("Service authorization request not prepared. Call getServiceAuthorizationUrl() first")
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }


    class AuthorizedImpl(
        @VisibleForTesting internal val serverState: String,
        @VisibleForTesting internal val client: CSCClient,
        @VisibleForTesting internal val serviceAccessAuthorized: ServiceAccessAuthorized,
    ) : RQESService.Authorized {

        @VisibleForTesting
        internal lateinit var documentsToSign: List<DocumentToSign>

        @VisibleForTesting
        internal lateinit var documentDigestList: DocumentDigestList

        @VisibleForTesting
        internal lateinit var credAuthRequestPrepared: CredentialAuthorizationRequestPrepared

        override suspend fun listCredentials(request: CredentialsListRequest?): Result<List<CredentialInfo>> {
            return try {
                with(client) {
                    val credentials = serviceAccessAuthorized.listCredentials(
                        request ?: CredentialsListRequest(onlyValid = true)
                    ).getOrThrow()

                    Result.success(credentials)
                }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

        override suspend fun getCredentialAuthorizationUrl(
            credential: CredentialInfo,
            documents: UnsignedDocuments,
        ): Result<HttpsUrl> {
            return try {
                with(client) {
                    this@AuthorizedImpl.documentsToSign = documents.asDocumentToSignList
                    this@AuthorizedImpl.documentDigestList = calculateDocumentHashes(
                        documents = documentsToSign,
                        hashAlgorithmOID = documents.hashAlgorithmOID,
                        credentialCertificate = credential.certificate
                    )
                    val authorizationCodeURL = serviceAccessAuthorized
                        .prepareCredentialAuthorizationRequest(
                            credentialAuthorizationSubject = CredentialAuthorizationSubject(
                                credentialRef = CredentialRef.ByCredentialID(credential.credentialID),
                                documentDigestList = documentDigestList,
                            ),
                            walletState = serverState
                        )
                        .getOrThrow()
                        .also { credAuthRequestPrepared = it }
                        .authorizationRequestPrepared
                        .authorizationCodeURL

                    Result.success(authorizationCodeURL)
                }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

        override suspend fun authorizeCredential(authorizationCode: AuthorizationCode): Result<RQESService.CredentialAuthorized> {
            return try {
                check(::credAuthRequestPrepared.isInitialized && ::documentsToSign.isInitialized) {
                    "Credential authorization failed. Call getCredentialAuthorizationUrl() first"
                }
                with(client) {
                    val authorized = credAuthRequestPrepared
                        .authorizeWithAuthorizationCode(authorizationCode, serverState)
                        .getOrThrow()
                    return Result.success(
                        CredentialAuthorizedImpl(
                            client = this@with,
                            documentsToSign = documentsToSign,
                            documentDigestList = documentDigestList,
                            credentialAuthorized = authorized,
                        )
                    )
                }

            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }

    class CredentialAuthorizedImpl(
        @VisibleForTesting internal val client: CSCClient,
        @VisibleForTesting internal val documentsToSign: List<DocumentToSign>,
        @VisibleForTesting internal val documentDigestList: DocumentDigestList,
        @VisibleForTesting internal val credentialAuthorized: CredentialAuthorized,
    ) : RQESService.CredentialAuthorized {
        override suspend fun signDocuments(
            signingAlgorithmOID: SigningAlgorithmOID,
        ): Result<SignedDocuments> {
            return try {
                with(client) {
                    val signatureList = when (credentialAuthorized) {
                        is CredentialAuthorized.SCAL1 -> credentialAuthorized
                            .signHash(
                                documentDigestList = documentDigestList,
                                signingAlgorithmOID = signingAlgorithmOID
                            )

                        is CredentialAuthorized.SCAL2 -> credentialAuthorized
                            .signHash(signingAlgorithmOID)

                    }.getOrThrow()

                    val signedDocuments = getSignedDocuments(
                        documents = documentsToSign,
                        signatures = signatureList.signatures,
                        credentialCertificate = credentialAuthorized.credentialCertificate,
                        hashAlgorithmOID = documentDigestList.hashAlgorithmOID,
                        signatureTimestamp = documentDigestList.hashCalculationTime
                    ).let { SignedDocuments(it) }
                    Result.success(signedDocuments)
                }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }
}