package com.experterp.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WarehouseRequest {

    @NotBlank(message = "Name is required")
    private String name;

    private String location;

    // Where purchases for this warehouse are shipped to
    private com.experterp.backend.entity.Address address;
}
