package com.experterp.backend.entity;

import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.repository.StockMovementRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StockMovementTypesTest {

    // The stock queries add IN_TYPES and subtract everything else; a type missing here would count backwards
    @Test
    void stockQueriesTreatExactlyTheInboundTypesAsIncoming() {
        Set<String> inQueries = Arrays.stream(StockMovementRepository.IN_TYPES.split(","))
                .map(t -> t.trim().replace("'", ""))
                .collect(Collectors.toSet());
        Set<String> inEnum = Arrays.stream(MovementType.values())
                .filter(MovementType::isIn)
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(inQueries).isEqualTo(inEnum);
    }
}
