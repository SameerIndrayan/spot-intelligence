package com.sedai.spot.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sedai.spot.model.SpotPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AzureIngestionService implements CloudIngestionService {

    private static final Logger log = LoggerFactory.getLogger(AzureIngestionService.class);
    private static final String BASE_URL = "https://prices.azure.com/api/retail/prices";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    // Only fetch from a few regions to keep API calls low
    private static final List<String> TARGET_REGIONS = List.of(
        "eastus",
        "westus2",
        "westeurope"
    );

    // Cap how many pages we fetch per region (each page = 100 results)
    private static final int MAX_PAGES = 2;

    public AzureIngestionService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String getProvider() {
        return "AZURE";
    }

    @Override
    public List<SpotPrice> fetchLatestPrices() {
        List<SpotPrice> allPrices = new ArrayList<>();

        for (String region : TARGET_REGIONS) {
            try {
                List<SpotPrice> regionPrices = fetchRegionPrices(region);
                allPrices.addAll(regionPrices);
                log.info("[AZURE] {} — fetched {} prices", region, regionPrices.size());
            } catch (Exception e) {
                log.error("[AZURE] Failed to fetch {}: {}", region, e.getMessage());
            }
        }

        return allPrices;
    }

    private List<SpotPrice> fetchRegionPrices(String region) throws Exception {
        List<SpotPrice> prices = new ArrayList<>();

        // Build the API URL with OData filter for spot VMs in this region
        String filter = String.format(
            "serviceName eq 'Virtual Machines' " +
            "and priceType eq 'Consumption' " +
            "and contains(skuName, 'Spot') " +
            "and armRegionName eq '%s'",
            region
        );
        String url = BASE_URL + "?$filter=" + java.net.URLEncoder.encode(filter, "UTF-8");
        int page = 0;

        while (url != null && page < MAX_PAGES) {
            // Make the HTTP request
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("[AZURE] HTTP {} for {}", response.statusCode(), region);
                break;
            }

            // Parse the JSON response
            JsonNode root = mapper.readTree(response.body());
            JsonNode items = root.get("Items");

            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    SpotPrice sp = mapAzurePrice(item, region);
                    if (sp != null) {
                        prices.add(sp);
                    }
                }
            }

            // Azure paginates via NextPageLink
            JsonNode nextPage = root.get("NextPageLink");
            url = (nextPage != null && !nextPage.isNull()) ? nextPage.asText() : null;
            page++;
        }

        return prices;
    }

    /**
     * Convert one Azure JSON record into our normalized SpotPrice.
     * Returns null if the record should be skipped (e.g. $0 price).
     */
    private SpotPrice mapAzurePrice(JsonNode item, String region) {
        try {
            double retailPrice = item.get("retailPrice").asDouble();

            // Skip $0 prices (sometimes appear for promo SKUs)
            if (retailPrice <= 0) return null;

            // armSkuName is the cleanest instance identifier
            // e.g. "Standard_D4s_v3", "Standard_A1"
            String armSkuName = item.get("armSkuName").asText();
            String family = extractFamily(armSkuName);

            // Determine OS from productName
            String productName = item.get("productName").asText();
            String os = productName.toLowerCase().contains("windows") ? "Windows" : "Linux";

            SpotPrice sp = new SpotPrice();
            sp.setProvider("AZURE");
            sp.setRegion(region);
            sp.setZone(null);  // Azure doesn't expose AZ-level spot pricing
            sp.setInstanceFamily(family);
            sp.setInstanceSize(armSkuName);
            sp.setOs(os);
            sp.setArch(guessArch(armSkuName));
            sp.setPriceUsd(BigDecimal.valueOf(retailPrice));
            sp.setEffectiveTime(parseDate(item.get("effectiveStartDate")));
            sp.setIngestedAt(Instant.now());

            return sp;
        } catch (Exception e) {
            log.warn("[AZURE] Skipping bad record: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract the family prefix from an Azure SKU name.
     * "Standard_D4s_v3" → "Standard_D"
     * "Standard_E16ads_v5" → "Standard_E"
     */
    private String extractFamily(String armSkuName) {
        // Remove "Standard_" prefix, find first digit, take everything before it
        String afterPrefix = armSkuName.replace("Standard_", "").replace("Basic_", "");
        StringBuilder family = new StringBuilder();
        for (char c : afterPrefix.toCharArray()) {
            if (Character.isDigit(c)) break;
            family.append(c);
        }
        return "Standard_" + family;
    }

    private String guessArch(String armSkuName) {
        // Azure arm64 VMs typically have "p" in the family (Dps, Eps, etc.)
        String lower = armSkuName.toLowerCase();
        if (lower.contains("_dp") || lower.contains("_ep") || lower.contains("pls")
            || lower.contains("pds") || lower.contains("pas")) {
            return "arm64";
        }
        return "x86_64";
    }

    private Instant parseDate(JsonNode dateNode) {
        if (dateNode != null && !dateNode.isNull()) {
            try {
                return Instant.parse(dateNode.asText());
            } catch (Exception e) {
                // Fall through
            }
        }
        return Instant.now();
    }
}