package com.sedai.spot.repository;

import com.sedai.spot.model.SpotPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpotPriceRepository extends JpaRepository<SpotPrice, Long> {

    List<SpotPrice> findByProviderAndRegion(String provider, String region);

    long countByProvider(String provider);
}