package com.charging.mock.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Model class representing a charge record, mapping to the backend
 * {@code ChargeRecord} entity.
 *
 * Fields are deserialized from the backend JSON response. All monetary
 * values use {@link BigDecimal} for precision.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChargeRecord {

    @JsonAlias({"id", "recordId"})
    private String id;
    private String userId;
    private String chargerId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private BigDecimal energyKwh;
    private BigDecimal fee;
    private String status;
    @JsonProperty("deductionStatus")
    private String deductionStatus;
    private LocalDateTime createdAt;

    public ChargeRecord() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getChargerId() {
        return chargerId;
    }

    public void setChargerId(String chargerId) {
        this.chargerId = chargerId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public BigDecimal getEnergyKwh() {
        return energyKwh;
    }

    public void setEnergyKwh(BigDecimal energyKwh) {
        this.energyKwh = energyKwh;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDeductionStatus() {
        return deductionStatus;
    }

    public void setDeductionStatus(String deductionStatus) {
        this.deductionStatus = deductionStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "ChargeRecord{" +
                "id='" + id + '\'' +
                ", chargerId='" + chargerId + '\'' +
                ", status='" + status + '\'' +
                ", energyKwh=" + energyKwh +
                ", fee=" + fee +
                '}';
    }
}