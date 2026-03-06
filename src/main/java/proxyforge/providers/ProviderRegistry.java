package proxyforge.providers;

import com.fasterxml.jackson.databind.JsonNode;
import proxyforge.models.ProxyForgeModels;
import proxyforge.models.ProxyForgeModels.DeployRequest;
import proxyforge.models.ProxyForgeModels.ProviderResult;
import proxyforge.models.ProxyForgeModels.ProviderType;
import proxyforge.models.ProxyForgeModels.ProxyEntry;
import proxyforge.models.ProxyForgeModels.ProxyMode;
import proxyforge.utils.ProxyForgeHttp;
import proxyforge.utils.ProxyForgeJson;
import proxyforge.utils.ProxyForgeLogger;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.apigateway.model.CreateResourceRequest;
import software.amazon.awssdk.services.apigateway.model.CreateRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.DeleteRestApiRequest;
import software.amazon.awssdk.services.apigateway.model.GetResourcesRequest;
import software.amazon.awssdk.services.apigateway.model.GetRestApisRequest;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.PutIntegrationRequest;
import software.amazon.awssdk.services.apigateway.model.PutMethodRequest;
import software.amazon.awssdk.services.apigateway.model.Resource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.RunInstancesResponse;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ProviderRegistry
{
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

    private final ProxyForgeLogger logger;
    private final HttpClient httpClient = ProxyForgeHttp.newHttpClient(HTTP_TIMEOUT);
    private final Map<ProviderType, ProxyProvider> providers = new LinkedHashMap<>();

    public ProviderRegistry(ProxyForgeLogger logger)
    {
        this.logger = logger;
        providers.put(ProviderType.AWS_FIREPROX, new AwsFireproxProvider());
        providers.put(ProviderType.CLOUDFLARE_FLAREPROX, new CloudflareFlareproxProvider());
        providers.put(ProviderType.VPS_FORGE, new VpsForgeProvider());
    }

    public ProviderResult deploy(DeployRequest request)
    {
        return provider(request.providerType()).deploy(request);
    }

    public ProviderResult list(ProviderType providerType, Map<String, String> fields)
    {
        return provider(providerType).list(fields);
    }

    public ProviderResult delete(ProxyEntry proxyEntry, Map<String, String> fields)
    {
        return provider(proxyEntry.providerType).delete(proxyEntry, fields);
    }

    private ProxyProvider provider(ProviderType providerType)
    {
        ProxyProvider provider = providers.get(providerType);
        if (provider == null)
        {
            throw new IllegalArgumentException("Unsupported provider: " + providerType);
        }
        return provider;
    }

    private interface ProxyProvider
    {
        ProviderResult deploy(DeployRequest request);

        ProviderResult list(Map<String, String> fields);

        ProviderResult delete(ProxyEntry proxyEntry, Map<String, String> fields);
    }

    private final class AwsFireproxProvider implements ProxyProvider
    {
        @Override
        public ProviderResult deploy(DeployRequest request)
        {
            String targetUrl;
            try
            {
                targetUrl = normalizeTargetUrl(request.field("targetUrl"));
            }
            catch (IllegalArgumentException exception)
            {
                return ProviderResult.failure(exception.getMessage());
            }
            String region = trim(request.field("region"));
            if (isBlank(targetUrl))
            {
                return ProviderResult.failure("AWS Fireprox requires a target URL.");
            }
            if (isBlank(request.field("accessKey")) || isBlank(request.field("secretKey")))
            {
                return ProviderResult.failure("AWS Fireprox requires both accessKey and secretKey.");
            }
            if (isBlank(region))
            {
                return ProviderResult.failure("AWS Fireprox requires a region.");
            }

            try (ApiGatewayClient client = apiGatewayClient(request.fields()))
            {
                URI target = URI.create(targetUrl);
                String apiName = "proxyforge-" + shortId();
                String apiId = client.createRestApi(CreateRestApiRequest.builder()
                    .name(apiName)
                    .description("ProxyForge Fireprox target " + target.getHost())
                    .build()).id();

                String rootId = client.getResources(GetResourcesRequest.builder().restApiId(apiId).build()).items()
                    .stream()
                    .filter(resource -> "/".equals(resource.path()))
                    .findFirst()
                    .map(Resource::id)
                    .orElseThrow(() -> new IllegalStateException("Unable to locate API Gateway root resource"));

                client.putMethod(PutMethodRequest.builder()
                    .restApiId(apiId)
                    .resourceId(rootId)
                    .httpMethod("ANY")
                    .authorizationType("NONE")
                    .build());

                client.putIntegration(PutIntegrationRequest.builder()
                    .restApiId(apiId)
                    .resourceId(rootId)
                    .httpMethod("ANY")
                    .type(IntegrationType.HTTP_PROXY)
                    .integrationHttpMethod("ANY")
                    .uri(trimTrailingSlash(targetUrl))
                    .build());

                String proxyResourceId = client.createResource(CreateResourceRequest.builder()
                    .restApiId(apiId)
                    .parentId(rootId)
                    .pathPart("{proxy+}")
                    .build()).id();

                client.putMethod(PutMethodRequest.builder()
                    .restApiId(apiId)
                    .resourceId(proxyResourceId)
                    .httpMethod("ANY")
                    .authorizationType("NONE")
                    .requestParameters(Map.of("method.request.path.proxy", true))
                    .build());

                client.putIntegration(PutIntegrationRequest.builder()
                    .restApiId(apiId)
                    .resourceId(proxyResourceId)
                    .httpMethod("ANY")
                    .type(IntegrationType.HTTP_PROXY)
                    .integrationHttpMethod("ANY")
                    .uri(trimTrailingSlash(targetUrl) + "/{proxy}")
                    .requestParameters(Map.of("integration.request.path.proxy", "method.request.path.proxy"))
                    .build());

                client.createDeployment(CreateDeploymentRequest.builder()
                    .restApiId(apiId)
                    .stageName("proxy")
                    .description("ProxyForge deployment")
                    .build());

                ProxyEntry entry = ProxyEntry.forwarder(
                    ProviderType.AWS_FIREPROX,
                    apiName,
                    "https://" + apiId + ".execute-api." + region + ".amazonaws.com/proxy/",
                    trimTrailingSlash(targetUrl));
                entry.providerResourceId = apiId;
                entry.metadata.put("region", region);
                entry.metadata.put("apiName", apiName);
                return ProviderResult.success("AWS Fireprox deployment created: " + entry.forwarderBaseUrl, entry);
            }
            catch (Exception exception)
            {
                logger.error("AWS Fireprox deployment failed", exception);
                return ProviderResult.failure("AWS deployment failed: " + exception.getMessage());
            }
        }

        @Override
        public ProviderResult list(Map<String, String> fields)
        {
            if (isBlank(fields.get("accessKey")) || isBlank(fields.get("secretKey")))
            {
                return ProviderResult.failure("AWS list requires both accessKey and secretKey.");
            }

            String region = trim(fields.get("region"));
            if (isBlank(region))
            {
                return ProviderResult.failure("AWS list requires a region.");
            }
            try (ApiGatewayClient client = apiGatewayClient(fields))
            {
                List<ProxyEntry> entries = new ArrayList<>();
                client.getRestApis(GetRestApisRequest.builder().limit(500).build()).items().forEach(api ->
                {
                    String name = Objects.toString(api.name(), "");
                    if (!name.startsWith("proxyforge-"))
                    {
                        return;
                    }

                    ProxyEntry entry = ProxyEntry.forwarder(
                        ProviderType.AWS_FIREPROX,
                        name,
                        "https://" + api.id() + ".execute-api." + region + ".amazonaws.com/proxy/",
                        "");
                    entry.providerResourceId = api.id();
                    entry.metadata.put("region", region);
                    entries.add(entry);
                });
                return ProviderResult.successList("Loaded " + entries.size() + " AWS Fireprox API Gateway entries.", entries);
            }
            catch (Exception exception)
            {
                logger.error("Unable to list AWS Fireprox APIs", exception);
                return ProviderResult.failure("AWS list failed: " + exception.getMessage());
            }
        }

        @Override
        public ProviderResult delete(ProxyEntry proxyEntry, Map<String, String> fields)
        {
            if (isBlank(fields.get("accessKey")) || isBlank(fields.get("secretKey")))
            {
                return ProviderResult.failure("AWS delete requires both accessKey and secretKey.");
            }
            if (isBlank(fields.get("region")))
            {
                return ProviderResult.failure("AWS delete requires a region.");
            }

            try (ApiGatewayClient client = apiGatewayClient(fields))
            {
                client.deleteRestApi(DeleteRestApiRequest.builder().restApiId(proxyEntry.providerResourceId).build());
                return ProviderResult.success("Deleted AWS Fireprox API " + proxyEntry.providerResourceId, proxyEntry);
            }
            catch (Exception exception)
            {
                logger.error("Unable to delete AWS Fireprox deployment", exception);
                return ProviderResult.failure("AWS delete failed: " + exception.getMessage());
            }
        }

        private ApiGatewayClient apiGatewayClient(Map<String, String> fields)
        {
            String sessionToken = trim(fields.get("sessionToken"));
            StaticCredentialsProvider credentialsProvider;
            if (isBlank(sessionToken))
            {
                credentialsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create(fields.get("accessKey"), fields.get("secretKey")));
            }
            else
            {
                credentialsProvider = StaticCredentialsProvider.create(AwsSessionCredentials.create(
                    fields.get("accessKey"),
                    fields.get("secretKey"),
                    sessionToken));
            }

            return ApiGatewayClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(trim(fields.get("region"))))
                .credentialsProvider(credentialsProvider)
                .build();
        }
    }

    private final class CloudflareFlareproxProvider implements ProxyProvider
    {
        @Override
        public ProviderResult deploy(DeployRequest request)
        {
            String targetUrl;
            String workersSubdomain;
            try
            {
                targetUrl = normalizeTargetUrl(request.field("targetUrl"));
                workersSubdomain = normalizeWorkersSubdomain(request.field("workersSubdomain"));
            }
            catch (IllegalArgumentException exception)
            {
                return ProviderResult.failure(exception.getMessage());
            }
            String accountId = trim(request.field("accountId"));
            String apiToken = trim(request.field("apiToken"));

            if (isBlank(targetUrl))
            {
                return ProviderResult.failure("Cloudflare deployment requires a target URL.");
            }
            if (isBlank(accountId) || isBlank(apiToken))
            {
                return ProviderResult.failure("Cloudflare deployment requires both accountId and apiToken.");
            }

            if (isBlank(workersSubdomain))
            {
                return ProviderResult.failure("Cloudflare workers subdomain is required. Enter only the account subdomain, not the full workers.dev URL.");
            }

            String scriptName = "proxyforge-" + shortId();
            try
            {
                String workerScript = resourceText("worker/cloudflare-worker.js")
                    .replace("__DEFAULT_TARGET__", trimTrailingSlash(targetUrl));
                String metadata = """
                    {"main_module":"worker.js","bindings":[]}
                    """.trim();

                ProxyForgeHttp.MultipartBody multipartBody = ProxyForgeHttp.multipartBody(
                    Map.of("metadata", metadata),
                    Map.of("worker.js", workerScript.getBytes(StandardCharsets.UTF_8)),
                    "application/javascript+module");

                HttpRequest requestMessage = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.cloudflare.com/client/v4/accounts/" + accountId + "/workers/scripts/" + scriptName))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + multipartBody.boundary())
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(multipartBody.body()))
                    .build();

                HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, requestMessage, 3, Duration.ofSeconds(1), logger);
                if (response.statusCode() >= 300)
                {
                    return ProviderResult.failure("Cloudflare deploy failed: HTTP " + response.statusCode() + " " + response.body());
                }
                JsonNode root = ProxyForgeJson.mapper().readTree(response.body());
                if (!root.path("success").asBoolean(true))
                {
                    return ProviderResult.failure("Cloudflare deploy failed: " + cloudflareErrors(root));
                }

                String forwarderUrl = workersDevEndpoint(scriptName, workersSubdomain);
                ProviderResult subdomainResult = enableCloudflareWorkersDevSubdomain(accountId, apiToken, scriptName);
                if (!subdomainResult.success())
                {
                    return subdomainResult;
                }
                ProviderResult verificationResult = verifyCloudflareForwarder(forwarderUrl);
                if (!verificationResult.success())
                {
                    return verificationResult;
                }

                ProxyEntry entry = ProxyEntry.forwarder(
                    ProviderType.CLOUDFLARE_FLAREPROX,
                    "Flareprox " + scriptName,
                    forwarderUrl,
                    trimTrailingSlash(targetUrl));
                entry.providerResourceId = scriptName;
                entry.metadata.put("scriptName", scriptName);
                entry.metadata.put("workersSubdomain", workersSubdomain);
                entry.metadata.put("targetUrl", targetUrl);
                return ProviderResult.success("Cloudflare Worker deployed: " + entry.forwarderBaseUrl, entry);
            }
            catch (Exception exception)
            {
                logger.error("Cloudflare Flareprox deployment failed", exception);
                return ProviderResult.failure("Cloudflare deployment failed: " + exception.getMessage());
            }
        }

        @Override
        public ProviderResult list(Map<String, String> fields)
        {
            String accountId = trim(fields.get("accountId"));
            String apiToken = trim(fields.get("apiToken"));
            String workersSubdomain;
            try
            {
                workersSubdomain = normalizeWorkersSubdomain(fields.get("workersSubdomain"));
            }
            catch (IllegalArgumentException exception)
            {
                return ProviderResult.failure(exception.getMessage());
            }
            if (isBlank(accountId) || isBlank(apiToken))
            {
                return ProviderResult.failure("Cloudflare list requires both accountId and apiToken.");
            }
            if (isBlank(workersSubdomain))
            {
                return ProviderResult.failure("Workers subdomain is required to build usable Cloudflare endpoints. Enter only the account subdomain, not the full workers.dev URL.");
            }

            try
            {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.cloudflare.com/client/v4/accounts/" + accountId + "/workers/scripts"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + apiToken)
                    .GET()
                    .build();

                HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, request, 3, Duration.ofSeconds(1), logger);
                if (response.statusCode() >= 300)
                {
                    return ProviderResult.failure("Cloudflare list failed: HTTP " + response.statusCode() + " " + response.body());
                }

                JsonNode root = ProxyForgeJson.mapper().readTree(response.body());
                if (!root.path("success").asBoolean(true))
                {
                    return ProviderResult.failure("Cloudflare list failed: " + cloudflareErrors(root));
                }
                List<ProxyEntry> entries = new ArrayList<>();
                for (JsonNode node : root.path("result"))
                {
                    String id = cloudflareScriptIdentifier(node);
                    if (!id.startsWith("proxyforge-"))
                    {
                        continue;
                    }

                    ProxyEntry entry = ProxyEntry.forwarder(
                        ProviderType.CLOUDFLARE_FLAREPROX,
                        "Flareprox " + id,
                        workersDevEndpoint(id, workersSubdomain),
                        "");
                    entry.providerResourceId = id;
                    entry.metadata.put("scriptName", id);
                    entry.metadata.put("workersSubdomain", workersSubdomain);
                    entries.add(entry);
                }
                return ProviderResult.successList("Loaded " + entries.size() + " Cloudflare Worker entries.", entries);
            }
            catch (Exception exception)
            {
                logger.error("Unable to list Cloudflare Workers", exception);
                return ProviderResult.failure("Cloudflare list failed: " + exception.getMessage());
            }
        }

        @Override
        public ProviderResult delete(ProxyEntry proxyEntry, Map<String, String> fields)
        {
            if (isBlank(fields.get("accountId")) || isBlank(fields.get("apiToken")))
            {
                return ProviderResult.failure("Cloudflare delete requires both accountId and apiToken.");
            }

            try
            {
                String accountId = trim(fields.get("accountId"));
                String apiToken = trim(fields.get("apiToken"));
                String scriptName = defaultString(proxyEntry.metadata.get("scriptName"), proxyEntry.providerResourceId);
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.cloudflare.com/client/v4/accounts/" + accountId + "/workers/scripts/" + scriptName))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + apiToken)
                    .DELETE()
                    .build();
                HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, request, 3, Duration.ofSeconds(1), logger);
                if (response.statusCode() >= 300)
                {
                    return ProviderResult.failure("Cloudflare delete failed: HTTP " + response.statusCode() + " " + response.body());
                }
                JsonNode root = ProxyForgeJson.mapper().readTree(response.body());
                if (!root.path("success").asBoolean(true))
                {
                    if (cloudflareHasErrorCode(root, 10007))
                    {
                        return ProviderResult.success("Cloudflare Worker " + scriptName + " was already absent remotely; removed local entry.", proxyEntry);
                    }
                    return ProviderResult.failure("Cloudflare delete failed: " + cloudflareErrors(root));
                }
                return ProviderResult.success("Deleted Cloudflare Worker " + scriptName, proxyEntry);
            }
            catch (Exception exception)
            {
                logger.error("Unable to delete Cloudflare Worker", exception);
                return ProviderResult.failure("Cloudflare delete failed: " + exception.getMessage());
            }
        }
    }

    private final class VpsForgeProvider implements ProxyProvider
    {
        @Override
        public ProviderResult deploy(DeployRequest request)
        {
            String vendor = normalizeVendor(request.field("vendor"));
            String region = trim(request.field("region"));
            String instanceType = trim(request.field("instanceType"));
            String apiToken = trim(request.field("apiToken"));

            if (isBlank(vendor))
            {
                return ProviderResult.failure("VPS deployment requires a vendor selection.");
            }
            if (isBlank(apiToken) && !"awsec2".equals(vendor))
            {
                return ProviderResult.failure("VPS deployment requires an API token for the selected vendor.");
            }

            return switch (vendor)
            {
                case "digitalocean" -> createDigitalOceanDroplet(apiToken, region, instanceType);
                case "linode" -> createLinodeInstance(apiToken, region, instanceType);
                case "awsec2" -> createAwsEc2Instance(request.fields());
                default -> ProviderResult.failure("Unsupported VPS vendor: " + vendor);
            };
        }

        @Override
        public ProviderResult list(Map<String, String> fields)
        {
            return ProviderResult.successList("Remote VPS listing is intentionally scoped to provider-specific state. Use local pool for active instances.", List.of());
        }

        @Override
        public ProviderResult delete(ProxyEntry proxyEntry, Map<String, String> fields)
        {
            String vendor = normalizeVendor(proxyEntry.metadata.get("vendor"));
            try
            {
                return switch (vendor)
                {
                    case "digitalocean" -> deleteDigitalOceanDroplet(fields.get("apiToken"), proxyEntry.providerResourceId, proxyEntry);
                    case "linode" -> deleteLinodeInstance(fields.get("apiToken"), proxyEntry.providerResourceId, proxyEntry);
                    case "awsec2" -> deleteAwsEc2Instance(fields, proxyEntry);
                    default -> ProviderResult.failure("Unsupported VPS vendor: " + vendor);
                };
            }
            catch (Exception exception)
            {
                logger.error("Unable to delete VPS instance", exception);
                return ProviderResult.failure("VPS delete failed: " + exception.getMessage());
            }
        }

        private ProviderResult createDigitalOceanDroplet(String apiToken, String region, String instanceType)
        {
            try
            {
                String password = "pf-" + shortId() + "!";
                String body = ProxyForgeJson.write(Map.of(
                    "name", "proxyforge-" + shortId(),
                    "region", defaultString(region, "nyc1"),
                    "size", defaultString(instanceType, "s-1vcpu-1gb"),
                    "image", "ubuntu-24-04-x64",
                    "user_data", buildTinyProxyCloudInit(password)));

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.digitalocean.com/v2/droplets"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

                HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, request, 3, Duration.ofSeconds(2), logger);
                if (response.statusCode() >= 300)
                {
                    return ProviderResult.failure("DigitalOcean create failed: HTTP " + response.statusCode() + " " + response.body());
                }

                JsonNode droplet = ProxyForgeJson.mapper().readTree(response.body()).path("droplet");
                String dropletId = droplet.path("id").asText();
                String host = waitForDigitalOceanIp(apiToken, dropletId);
                ProxyEntry entry = ProxyEntry.networkProxy(
                    ProviderType.VPS_FORGE,
                    ProxyMode.HTTP_PROXY,
                    "DO VPS " + dropletId,
                    host,
                    3128,
                    "",
                    password);
                entry.providerResourceId = dropletId;
                entry.metadata.put("vendor", "digitalocean");
                entry.metadata.put("region", region);
                entry.metadata.put("instanceType", instanceType);
                return ProviderResult.success("DigitalOcean proxy ready at " + host + ":3128", entry);
            }
            catch (Exception exception)
            {
                logger.error("DigitalOcean droplet deployment failed", exception);
                return ProviderResult.failure("DigitalOcean deployment failed: " + exception.getMessage());
            }
        }

        private ProviderResult createLinodeInstance(String apiToken, String region, String instanceType)
        {
            try
            {
                String password = "pf-" + shortId() + "!";
                String body = ProxyForgeJson.write(Map.of(
                    "region", defaultString(region, "us-east"),
                    "type", defaultString(instanceType, "g6-nanode-1"),
                    "label", "proxyforge-" + shortId(),
                    "image", "linode/ubuntu24.04",
                    "root_pass", password,
                    "stackscript_data", Map.of(),
                    "metadata", Map.of("user_data", Base64.getEncoder().encodeToString(buildTinyProxyCloudInit(password).getBytes(StandardCharsets.UTF_8)))));

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.linode.com/v4/linode/instances"))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

                HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, request, 3, Duration.ofSeconds(2), logger);
                if (response.statusCode() >= 300)
                {
                    return ProviderResult.failure("Linode create failed: HTTP " + response.statusCode() + " " + response.body());
                }

                JsonNode linode = ProxyForgeJson.mapper().readTree(response.body());
                String instanceId = linode.path("id").asText();
                String host = waitForLinodeIp(apiToken, instanceId);
                ProxyEntry entry = ProxyEntry.networkProxy(
                    ProviderType.VPS_FORGE,
                    ProxyMode.HTTP_PROXY,
                    "Linode VPS " + instanceId,
                    host,
                    3128,
                    "",
                    password);
                entry.providerResourceId = instanceId;
                entry.metadata.put("vendor", "linode");
                entry.metadata.put("region", region);
                entry.metadata.put("instanceType", instanceType);
                return ProviderResult.success("Linode proxy ready at " + host + ":3128", entry);
            }
            catch (Exception exception)
            {
                logger.error("Linode deployment failed", exception);
                return ProviderResult.failure("Linode deployment failed: " + exception.getMessage());
            }
        }

        private ProviderResult createAwsEc2Instance(Map<String, String> fields)
        {
            String accessKey = trim(fields.get("accessKey"));
            String secretKey = trim(fields.get("secretKey"));
            String region = trim(fields.get("region"));
            String instanceType = trim(fields.get("instanceType"));
            String amiId = trim(fields.get("amiId"));
            if (isBlank(accessKey) || isBlank(secretKey) || isBlank(region) || isBlank(amiId))
            {
                return ProviderResult.failure("AWS EC2 provisioning requires accessKey, secretKey, region, and amiId.");
            }

            String password = "pf-" + shortId() + "!";
            try (Ec2Client client = Ec2Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build())
            {
                RunInstancesRequest.Builder builder = RunInstancesRequest.builder()
                    .imageId(amiId)
                    .instanceType(InstanceType.fromValue(defaultString(instanceType, "t3.micro")))
                    .minCount(1)
                    .maxCount(1)
                    .userData(Base64.getEncoder().encodeToString(buildTinyProxyCloudInit(password).getBytes(StandardCharsets.UTF_8)));

                if (!isBlank(fields.get("iamInstanceProfile")))
                {
                    builder.iamInstanceProfile(IamInstanceProfileSpecification.builder().name(fields.get("iamInstanceProfile")).build());
                }

                if (!isBlank(fields.get("subnetId")))
                {
                    builder.subnetId(fields.get("subnetId"));
                }

                if (!isBlank(fields.get("securityGroupId")))
                {
                    builder.securityGroupIds(fields.get("securityGroupId"));
                }

                RunInstancesResponse response = client.runInstances(builder.build());
                String instanceId = response.instances().getFirst().instanceId();
                ProxyEntry entry = ProxyEntry.networkProxy(
                    ProviderType.VPS_FORGE,
                    ProxyMode.HTTP_PROXY,
                    "EC2 VPS " + instanceId,
                    "",
                    3128,
                    "",
                    password);
                entry.providerResourceId = instanceId;
                entry.status = ProxyForgeModels.ProxyStatus.DEPLOYING;
                entry.metadata.put("vendor", "awsec2");
                entry.metadata.put("region", region);
                entry.metadata.put("instanceType", instanceType);
                entry.metadata.put("amiId", amiId);
                return ProviderResult.success("EC2 instance launched: " + instanceId + " (await public IP in cloud console)", entry);
            }
            catch (Exception exception)
            {
                logger.error("AWS EC2 deployment failed", exception);
                return ProviderResult.failure("AWS EC2 deployment failed: " + exception.getMessage());
            }
        }

        private ProviderResult deleteDigitalOceanDroplet(String apiToken, String resourceId, ProxyEntry proxyEntry) throws IOException, InterruptedException
        {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.digitalocean.com/v2/droplets/" + URLEncoder.encode(resourceId, StandardCharsets.UTF_8)))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + apiToken)
                .DELETE()
                .build();
            HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, request, 3, Duration.ofSeconds(1), logger);
            if (response.statusCode() >= 300)
            {
                return ProviderResult.failure("DigitalOcean delete failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return ProviderResult.success("Deleted DigitalOcean droplet " + resourceId, proxyEntry);
        }

        private ProviderResult deleteLinodeInstance(String apiToken, String resourceId, ProxyEntry proxyEntry) throws IOException, InterruptedException
        {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.linode.com/v4/linode/instances/" + resourceId))
                .timeout(HTTP_TIMEOUT)
                .header("Authorization", "Bearer " + apiToken)
                .DELETE()
                .build();
            HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, request, 3, Duration.ofSeconds(1), logger);
            if (response.statusCode() >= 300)
            {
                return ProviderResult.failure("Linode delete failed: HTTP " + response.statusCode() + " " + response.body());
            }
            return ProviderResult.success("Deleted Linode instance " + resourceId, proxyEntry);
        }

        private ProviderResult deleteAwsEc2Instance(Map<String, String> fields, ProxyEntry proxyEntry)
        {
            try (Ec2Client client = Ec2Client.builder()
                .region(Region.of(trim(fields.get("region"))))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(fields.get("accessKey"), fields.get("secretKey"))))
                .build())
            {
                client.terminateInstances(TerminateInstancesRequest.builder().instanceIds(proxyEntry.providerResourceId).build());
                return ProviderResult.success("Terminated EC2 instance " + proxyEntry.providerResourceId, proxyEntry);
            }
            catch (Exception exception)
            {
                logger.error("Unable to terminate EC2 instance", exception);
                return ProviderResult.failure("AWS EC2 delete failed: " + exception.getMessage());
            }
        }

        private String waitForDigitalOceanIp(String apiToken, String dropletId) throws IOException, InterruptedException
        {
            Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
            while (Instant.now().isBefore(deadline))
            {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.digitalocean.com/v2/droplets/" + dropletId))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + apiToken)
                    .GET()
                    .build();
                HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, request, 2, Duration.ofSeconds(1), logger);
                JsonNode node = ProxyForgeJson.mapper().readTree(response.body()).path("droplet").path("networks").path("v4");
                for (JsonNode network : node)
                {
                    if ("public".equalsIgnoreCase(network.path("type").asText()))
                    {
                        return network.path("ip_address").asText();
                    }
                }
                Thread.sleep(5_000L);
            }
            throw new IOException("Timed out waiting for DigitalOcean public IP");
        }

        private String waitForLinodeIp(String apiToken, String instanceId) throws IOException, InterruptedException
        {
            Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
            while (Instant.now().isBefore(deadline))
            {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.linode.com/v4/linode/instances/" + instanceId))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + apiToken)
                    .GET()
                    .build();
                HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, request, 2, Duration.ofSeconds(1), logger);
                JsonNode ipv4 = ProxyForgeJson.mapper().readTree(response.body()).path("ipv4");
                if (ipv4.isArray() && !ipv4.isEmpty())
                {
                    return ipv4.get(0).asText();
                }
                Thread.sleep(5_000L);
            }
            throw new IOException("Timed out waiting for Linode public IP");
        }
    }

    private static String defaultTarget(String targetUrl)
    {
        return isBlank(targetUrl) ? "the configured target" : targetUrl;
    }

    private static String trimTrailingSlash(String value)
    {
        String trimmed = trim(value);
        while (trimmed.endsWith("/"))
        {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String trim(String value)
    {
        return value == null ? "" : value.trim();
    }

    static String normalizeTargetUrl(String value)
    {
        String normalized = trim(value);
        if (normalized.isEmpty())
        {
            return "";
        }
        if (!normalized.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$"))
        {
            normalized = "https://" + normalized;
        }

        URI uri = URI.create(normalized);
        if (isBlank(uri.getHost()))
        {
            throw new IllegalArgumentException("Target URL must include a valid host name.");
        }
        return trimTrailingSlash(uri.toString());
    }

    static String normalizeWorkersSubdomain(String value)
    {
        String normalized = trim(value).toLowerCase(Locale.ROOT);
        if (normalized.isEmpty())
        {
            return "";
        }

        if (normalized.startsWith("http://") || normalized.startsWith("https://"))
        {
            normalized = Objects.requireNonNullElse(URI.create(normalized).getHost(), "");
        }

        normalized = normalized.replaceAll("\\.+$", "");
        if (normalized.endsWith(".workers.dev"))
        {
            normalized = normalized.substring(0, normalized.length() - ".workers.dev".length());
        }

        if (normalized.equals("workers")
            || normalized.equals("workers.dev")
            || normalized.isBlank()
            || normalized.contains("/")
            || !normalized.matches("[a-z0-9-]+(\\.[a-z0-9-]+)*"))
        {
            throw new IllegalArgumentException("Workers subdomain must contain only the account subdomain, for example abc123.");
        }
        return normalized;
    }

    private static String workersDevEndpoint(String scriptName, String workersSubdomain)
    {
        return "https://" + scriptName + "." + workersSubdomain + ".workers.dev/";
    }

    private ProviderResult enableCloudflareWorkersDevSubdomain(String accountId, String apiToken, String scriptName)
    {
        List<String> errors = new ArrayList<>();
        String requestBody = "{\"enabled\":true}";

        for (String endpoint : List.of(
            "https://api.cloudflare.com/client/v4/accounts/" + accountId + "/workers/scripts/" + scriptName + "/subdomain",
            "https://api.cloudflare.com/client/v4/accounts/" + accountId + "/workers/services/" + scriptName + "/environments/production/subdomain"))
        {
            try
            {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(HTTP_TIMEOUT)
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
                HttpResponse<String> response = ProxyForgeHttp.sendWithRetry(httpClient, request, 3, Duration.ofSeconds(1), logger);
                if (response.statusCode() >= 400)
                {
                    errors.add("HTTP " + response.statusCode() + " for " + endpoint);
                    continue;
                }

                JsonNode root = ProxyForgeJson.mapper().readTree(response.body());
                if (root.path("success").asBoolean(true))
                {
                    return ProviderResult.success("Enabled workers.dev route for " + scriptName, null);
                }
                errors.add(cloudflareErrors(root));
            }
            catch (Exception exception)
            {
                errors.add(exception.getMessage());
            }
        }

        return ProviderResult.failure("Cloudflare deploy failed to enable workers.dev for " + scriptName + ": " + String.join("; ", errors));
    }

    private ProviderResult verifyCloudflareForwarder(String forwarderUrl)
    {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(45));
        String lastFailure = "Cloudflare forwarder endpoint did not become reachable";

        while (Instant.now().isBefore(deadline))
        {
            try
            {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(forwarderUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "ProxyForge/2")
                    .GET()
                    .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 500 && !looksLikeCloudflarePlaceholder(response.body()))
                {
                    return ProviderResult.success("Verified Cloudflare forwarder endpoint " + forwarderUrl, null);
                }

                lastFailure = response.statusCode() + " " + summarizeBody(response.body());
            }
            catch (Exception exception)
            {
                lastFailure = exception.getMessage();
            }

            try
            {
                Thread.sleep(3_000L);
            }
            catch (InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return ProviderResult.failure("Cloudflare forwarder did not become live at " + forwarderUrl + ": " + lastFailure);
    }

    private static String cloudflareErrors(JsonNode root)
    {
        List<String> errors = new ArrayList<>();
        for (JsonNode error : root.path("errors"))
        {
            String code = error.path("code").asText();
            String message = error.path("message").asText();
            if (!code.isBlank() || !message.isBlank())
            {
                errors.add((code.isBlank() ? "" : code + ": ") + message);
            }
        }
        return errors.isEmpty() ? "Unknown Cloudflare API error" : String.join("; ", errors);
    }

    private static boolean cloudflareHasErrorCode(JsonNode root, int expectedCode)
    {
        for (JsonNode error : root.path("errors"))
        {
            if (error.path("code").asInt() == expectedCode)
            {
                return true;
            }
        }
        return false;
    }

    private static String cloudflareScriptIdentifier(JsonNode node)
    {
        for (String field : List.of("id", "script", "name", "tag"))
        {
            String value = node.path(field).asText();
            if (!value.isBlank())
            {
                return value;
            }
        }
        return "";
    }

    static boolean looksLikeCloudflarePlaceholder(String body)
    {
        String normalized = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return normalized.contains("there is nothing here yet")
            || normalized.contains("please check back again later")
            || normalized.contains("page not found")
            || normalized.contains("error code: 10007");
    }

    private static String summarizeBody(String body)
    {
        if (body == null || body.isBlank())
        {
            return "empty response";
        }
        String singleLine = body.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 160 ? singleLine.substring(0, 160) + "..." : singleLine;
    }

    private static boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }

    private static String shortId()
    {
        return UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.ROOT);
    }

    private static String resourceText(String path) throws IOException
    {
        try (InputStream inputStream = ProviderRegistry.class.getClassLoader().getResourceAsStream(path))
        {
            if (inputStream == null)
            {
                throw new IOException("Missing resource " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalizeVendor(String vendor)
    {
        return trim(vendor).toLowerCase(Locale.ROOT).replace("_", "");
    }

    private static String defaultString(String value, String fallback)
    {
        return isBlank(value) ? fallback : value;
    }

    private static String buildTinyProxyCloudInit(String password)
    {
        return """
            #cloud-config
            package_update: true
            package_upgrade: true
            packages:
              - tinyproxy
            write_files:
              - path: /etc/tinyproxy/tinyproxy.conf
                permissions: '0644'
                content: |
                  User tinyproxy
                  Group tinyproxy
                  Port 3128
                  Timeout 600
                  Allow 0.0.0.0/0
                  BasicAuth proxyforge %s
                  ViaProxyName "ProxyForge"
                  MaxClients 200
                  MinSpareServers 5
                  MaxSpareServers 20
                  StartServers 10
                  DefaultErrorFile "/usr/share/tinyproxy/default.html"
                  StatFile "/usr/share/tinyproxy/stats.html"
                  LogLevel Info
                  ConnectPort 443
                  ConnectPort 563
            runcmd:
              - systemctl enable tinyproxy
              - systemctl restart tinyproxy
            """.formatted(password);
    }
}
