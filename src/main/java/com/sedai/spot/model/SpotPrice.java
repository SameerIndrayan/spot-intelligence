package com.sedai.spot.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "spot_price")
public class SpotPrice {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String provider;

    @Column(nullable = false, length = 50)
    private String region;

    @Column(length = 50)
    private String zone;

    @Column(name = "instance_family", nullable = false, length = 50)
    private String instanceFamily;

    @Column(name = "instance_size", nullable = false, length = 100)
    private String instanceSize;

    @Column(nullable = false, length = 20)
    private String os;

    @Column
    private String arch;

    @Column(name = "price_usd", nullable = false, precision = 10, scale = 6)
    private BigDecimal priceUsd;

    @Column(name = "effective_time", nullable = false)
    private Instant effectiveTime;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    public SpotPrice() {
        this.ingestedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }
    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getRegion() {
        return region;
    }
    public void setRegion(String region) {
        this.region = region;
    }

    public String getZone() {
        return zone;
    }
    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getInstanceFamily() {
        return instanceFamily;
    }
    public void setInstanceFamily(String instanceFamily) {
        this.instanceFamily = instanceFamily;
    }

    public String getInstanceSize() {
        return instanceSize;
    }
    public void setInstanceSize(String instanceSize) {
        this.instanceSize = instanceSize;
    }

    public String getOs() {
        return os;
    }
    public void setOs(String os) {
        this.os = os;
    }

    public String getArch() {
        return arch;
    }
    public void setArch(String arch) {
        this.arch = arch;
    }

    public BigDecimal getPrice() {
        return priceUsd;
    }
    public void setPriceUsd(BigDecimal priceUsd) {
        this.priceUsd = priceUsd;
    }

    public Instant getEffectiveTime() {
        return effectiveTime;
    }
    public void setEffectiveTime(Instant effectiveTime) {
        this.effectiveTime = effectiveTime;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }
    public void setIngestedAt(Instant ingestedAt) {
        this.ingestedAt = ingestedAt;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s / %s / %s - $%s", provider, region, instanceSize, os, priceUsd);
    }



}