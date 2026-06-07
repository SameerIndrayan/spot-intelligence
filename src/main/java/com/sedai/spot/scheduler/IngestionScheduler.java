package com.sedai.spot.scheduler;

import com.sedai.spot.ingestion.CloudIngestionService;
import com.sedai.spot.model.SpotPrice;
import com.sedai.spot.repository.SpotPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final List<CloudIngestionService> ingestionServices;
    private final SpotPriceRepository repository;

    // Spring auto-injects ALL classes that implement CloudIngestionService
    public IngestionScheduler(
            List<CloudIngestionService> ingestionServices,
            SpotPriceRepository repository) {
        this.ingestionServices = ingestionServices;
        this.repository = repository;
    }

    // Runs 5 seconds after startup, then every 5 minutes
    @Scheduled(fixedRate = 300000, initialDelay = 5000)
    public void runIngestion() {
        log.info("=== Ingestion cycle starting ===");

        for (CloudIngestionService service : ingestionServices) {
            if (!service.isEnabled()) {
                log.info("[{}] Skipped — disabled", service.getProvider());
                continue;
            }

            try {
                List<SpotPrice> prices = service.fetchLatestPrices();
                int saved = 0;

                for (SpotPrice price : prices) {
                    try {
                        repository.save(price);
                        saved++;
                    } catch (DataIntegrityViolationException e) {
                        // Duplicate — skip it silently
                    }
                }

                log.info("[{}] Saved {} new records ({} total fetched)",
                    service.getProvider(), saved, prices.size());

            } catch (Exception e) {
                log.error("[{}] Failed: {}", service.getProvider(), e.getMessage());
            }
        }

        log.info("=== Ingestion complete — {} total records in DB ===",
            repository.count());
    }
}