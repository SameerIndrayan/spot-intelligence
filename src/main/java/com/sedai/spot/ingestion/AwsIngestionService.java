package com.sedai.spot.ingestion;

import com.sedai.spot.model.SpotPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class AwsIngestionService implements CloudIngestionService {

    private static final Logger log = LoggerFactory.getLogger(AwsIngestionService.class);

    private static final List<Region> TARGET_REGIONS = List.of(
        Region.US_EAST_1,
        Region.US_WEST_2,
        Region.EU_WEST_1
    );

    @Override
    public String getProvider() {
        return "AWS";
    }

    @Override
    public List<SpotPrice> fetchLatestPrices() {
        List<SpotPrice> allPrices = new ArrayList<>();

        for (Region region : TARGET_REGIONS) {
            try {
                List<SpotPrice> regionPrices = fetchRegionPrices(region);
                allPrices.addAll(regionPrices);
                log.info("[AWS] {} — fetched {} prices", region.id(), regionPrices.size());
            } catch (Exception e) {
                log.error("[AWS] Failed to fetch {}: {}", region.id(), e.getMessage());
            }
        }

        return allPrices;
    }

    private List<SpotPrice> fetchRegionPrices(Region region) {
        List<SpotPrice> prices = new ArrayList<>();

        try (Ec2Client ec2 = Ec2Client.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build()) {

            // get prices from last 10 mins
            Instant startTime = Instant.now().minus(10, ChronoUnit.MINUTES);

            DescribeSpotPriceHistoryRequest request = DescribeSpotPriceHistoryRequest.builder()
                .startTime(startTime)
                .productDescriptions("Linux/UNIX")
                .maxResults(100)
                .build();

            DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(request);

            for (software.amazon.awssdk.services.ec2.model.SpotPrice awsPrice :
                    response.spotPriceHistory()) {

                SpotPrice sp = mapAwsPrice(awsPrice, region.id());
                if (sp != null) {
                    prices.add(sp);
                }
            }
        }

        return prices;
    }

    private SpotPrice mapAwsPrice(
            software.amazon.awssdk.services.ec2.model.SpotPrice awsPrice,
            String regionId) {
        try {
            String instanceType = awsPrice.instanceTypeAsString();

            //  m5.xlarge --> m5 fam
            String family = instanceType.contains(".")
                ? instanceType.substring(0, instanceType.indexOf('.'))
                : instanceType;

            SpotPrice sp = new SpotPrice();
            sp.setProvider("AWS");
            sp.setRegion(regionId);
            sp.setZone(awsPrice.availabilityZone());
            sp.setInstanceFamily(family);
            sp.setInstanceSize(instanceType);
            sp.setOs("Linux");
            sp.setArch(guessArch(family));
            sp.setPriceUsd(new BigDecimal(awsPrice.spotPrice()));
            sp.setEffectiveTime(awsPrice.timestamp());
            sp.setIngestedAt(Instant.now());

            return sp;
        } catch (Exception e) {
            log.warn("[AWS] Failed to map price: {}", e.getMessage());
            return null;
        }
    }

    private String guessArch(String family) {
        // graviton fam use arm64
        if (family.endsWith("g") || family.contains("6g") || family.contains("7g")) {
            return "arm64";
        }
        return "x86_64";
    }
}