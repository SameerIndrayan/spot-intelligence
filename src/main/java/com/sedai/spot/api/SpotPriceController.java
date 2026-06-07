package com.sedai.spot.api;

import com.sedai.spot.model.SpotPrice;
import com.sedai.spot.repository.SpotPriceRepository;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SpotPriceController {

    private final SpotPriceRepository repository;

    public SpotPriceController(SpotPriceRepository repository) {
        this.repository = repository;
    }

    // GET http://localhost:8080/api/prices?provider=AWS&region=us-east-1
    @GetMapping("/prices")
    public List<SpotPrice> getPrices(
            @RequestParam String provider,
            @RequestParam String region) {
        return repository.findByProviderAndRegion(provider, region);
    }

    // GET http://localhost:8080/api/health
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("totalRecords", repository.count());
        status.put("aws", repository.countByProvider("AWS"));
        status.put("azure", repository.countByProvider("AZURE"));
        return status;
    }
}