//[rqes-core](../../../index.md)/[eu.europa.ec.eudi.rqes.core](../index.md)/[RQESServiceImpl](index.md)/[RQESServiceImpl](-r-q-e-s-service-impl.md)

# RQESServiceImpl

[androidJvm]\
constructor(serviceEndpointUrl: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin-stdlib/kotlin/-string/index.html), config: CSCClientConfig, outputPathDir: [String](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin-stdlib/kotlin/-string/index.html), hashAlgorithm: HashAlgorithmOID, clientFactory: () -&gt; HttpClient? = null)

#### Parameters

androidJvm

| | |
|---|---|
| serviceEndpointUrl | The RQES service endpoint URL. |
| config | The RQES service configuration. |
| outputPathDir | Directory where signed documents will be stored. |
| hashAlgorithm | The algorithm OID, for hashing the documents. |
| clientFactory | The HTTP client factory. If this property is null, the default HTTP client factory will be used. |
