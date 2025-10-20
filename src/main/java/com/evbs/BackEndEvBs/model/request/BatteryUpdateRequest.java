package com.evbs.BackEndEvBs.model.request;

import com.evbs.BackEndEvBs.entity.Battery;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BatteryUpdateRequest {

    private String model;

    @DecimalMin(value = "0.0", inclusive = false, message = "Capacity must be greater than 0")
    @Digits(integer = 8, fraction = 2, message = "Capacity must have max 8 integer and 2 fraction digits")
    private BigDecimal capacity;

    @DecimalMin(value = "0.0", message = "State of health cannot be negative")
    @Digits(integer = 3, fraction = 2, message = "State of health must have max 3 integer and 2 fraction digits")
    private BigDecimal stateOfHealth;

    @Enumerated(EnumType.STRING)
    private Battery.Status status;

    // Thêm các trường mới
    private LocalDate manufactureDate;

    private LocalDate lastMaintenanceDate;

    private Long batteryTypeId;

    private Long currentStationId;
}