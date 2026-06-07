package com.sedai.spot.ingestion;

import com.sedai.spot.model.SpotPrice;
import java.util.List;



public interface CloudIngestionService {
    // returning prices as SpotPrice objs
    List<SpotPrice> fetchLatestPrices();

    String getProvider();

    default boolean isEnabled() {
        return true;
    }
}
