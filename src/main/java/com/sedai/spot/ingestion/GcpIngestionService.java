package com.sedai.spot.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sedai.spot.model.SpotPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class GcpIngestionService implements CloudIngestionService {

    private static final Logger log = LoggerFactory.getLogger(GcpIngestionService.class);

    // Compute Engine service ID in GCP's billing catalog
    private static final String COMPUTE_SERVICE_ID = "6F81-5844-456A";
    private static final String BASE_URL =
        "https://cloudbilling.googleapis.com/v1/services/" + COMPUTE_SERVICE_ID + "/skus";

    // 1 USD = 1,000,000,000 nanos
    private static final BigDecimal NANOS_PER_UNIT = new BigDecimal("1000000000");

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    private static final List<String> TARGET_REGIONS = List.of(
        "us-central1",
        "us-east1",
        "europe-west1"
    );

    @Value("${gcp.api-key:}")
    private String apiKey;

    private static final int MAX_PAGES = 5;

    public GcpIngestionService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String getProvider() {
        return "GCP";
    }

    @Override
    public boolean isEnabled() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[GCP] Disabled — no API key. Set GCP_API_KEY env var.");
            return false;
        }
        return true;
    }

    @Override
    public List<SpotPrice> fetchLatestPrices() {
        List<SpotPrice> allPrices = new ArrayList<>();

        try {
            List<SpotPrice> prices = fetchSpotSkus();
            allPrices.addAll(prices);
            log.info("[GCP] Fetched {} spot prices total", prices.size());
        } catch (Exception e) {
            log.error("[GCP] Ingestion failed: {}", e.getMessage());
        }

        return allPrices;
    }

    private List<SpotPrice> fetchSpotSkus() throws Exception {
        List<SpotPrice> prices = new ArrayList<>();
        String pageToken = null;
        int page = 0;

        while (page < MAX_PAGES) {
            String url = BASE_URL + "?key=" + apiKey + "&pageSize=100";
            if (pageToken != null) {
                url += "&pageToken=" + pageToken;
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[GCP] HTTP {}", response.statusCode());
                break;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode skus = root.get("skus");

            if (skus != null && skus.isArray()) {
                for (JsonNode sku : skus) {
                    String description = sku.has("description")
                        ? sku.get("description").asText() : "";

                    // Only process Spot/Preemptible VM SKUs
                    if (isSpotSku(description)) {
                        List<SpotPrice> mapped = mapGcpSku(sku);
                        prices.addAll(mapped);
                    }
                }
            }

            // Pagination
            JsonNode nextToken = root.get("nextPageToken");
            if (nextToken != null && !nextToken.isNull() && !nextToken.asText().isBlank()) {
                pageToken = nextToken.asText();
            } else {
                break;
            }
            page++;
        }

        return prices;
    }

    private boolean isSpotSku(String description) {
        String lower = description.toLowerCase();
        return (lower.contains("spot") || lower.contains("preemptible"))
            && lower.contains("instance")
            && !lower.contains("network")
            && !lower.contains("storage")
            && !lower.contains("license");
    }

    private List<SpotPrice> mapGcpSku(JsonNode sku) {
        List<SpotPrice> prices = new ArrayList<>();

        try {
            String description = sku.get("description").asText();
            String instanceType = extractInstanceType(description);
            if (instanceType == null) return prices;

            String family = instanceType.contains("-")
                ? instanceType.substring(0, instanceType.indexOf('-'))
                : instanceType;

            String os = description.toLowerCase().contains("windows") ? "Windows" : "Linux";

            // Get regions this SKU applies to
            JsonNode serviceRegions = sku.get("serviceRegions");
            if (serviceRegions == null) return prices;

            List<String> regions = new ArrayList<>();
            for (JsonNode r : serviceRegions) {
                if (TARGET_REGIONS.contains(r.asText())) {
                    regions.add(r.asText());
                }
            }
            if (regions.isEmpty()) return prices;

            // Extract price
            JsonNode pricingInfo = sku.get("pricingInfo");
            if (pricingInfo == null || !pricingInfo.isArray() || pricingInfo.isEmpty()) return prices;

            BigDecimal pricePerHour = extractHourlyPrice(pricingInfo.get(0));
            if (pricePerHour == null || pricePerHour.compareTo(BigDecimal.ZERO) <= 0) return prices;

            Instant effectiveTime = extractEffectiveTime(pricingInfo.get(0));

            // Create a record for each target region
            for (String region : regions) {
                SpotPrice sp = new SpotPrice();
                sp.setProvider("GCP");
                sp.setRegion(region);
                sp.setZone(null);
                sp.setInstanceFamily(family);
                sp.setInstanceSize(instanceType);
                sp.setOs(os);
                sp.setArch(instanceType.startsWith("t2a") ? "arm64" : "x86_64");
                sp.setPriceUsd(pricePerHour);
                sp.setEffectiveTime(effectiveTime);
                sp.setIngestedAt(Instant.now());
                prices.add(sp);
            }

        } catch (Exception e) {
            log.warn("[GCP] Failed to map SKU: {}", e.getMessage());
        }

        return prices;
    }

    private String extractInstanceType(String description) {
        String lower = description.toLowerCase();
        String[] families = {"n2d", "n2", "n1", "n4", "e2", "c2d", "c2", "c3d", "c3",
                             "m1", "m2", "m3", "t2d", "t2a", "a2", "g2", "c4", "n4"};
        for (String f : families) {
            if (lower.contains(f + " ") || lower.contains(f + "-")) {
                return f + "-standard";
            }
        }
        return null;
    }

    private BigDecimal extractHourlyPrice(JsonNode pricingInfo) {
        try {
            JsonNode tieredRates = pricingInfo
                .get("pricingExpression")
                .get("tieredRates");

            if (tieredRates == null || !tieredRates.isArray() || tieredRates.isEmpty()) return null;

            JsonNode unitPrice = tieredRates.get(0).get("unitPrice");
            long nanos = unitPrice.has("nanos") ? unitPrice.get("nanos").asLong() : 0;
            int units = unitPrice.has("units") ? unitPrice.get("units").asInt() : 0;

            // Convert: units + nanos/1B = price per hour
            return new BigDecimal(units)
                .add(new BigDecimal(nanos).divide(NANOS_PER_UNIT, MathContext.DECIMAL64));

        } catch (Exception e) {
            return null;
        }
    }

    private Instant extractEffectiveTime(JsonNode pricingInfo) {
        try {
            JsonNode effectiveTime = pricingInfo.get("effectiveTime");
            if (effectiveTime != null) {
                return Instant.parse(effectiveTime.asText());
            }
        } catch (Exception e) {
            // Fall through
        }
        return Instant.now();
    }
}

