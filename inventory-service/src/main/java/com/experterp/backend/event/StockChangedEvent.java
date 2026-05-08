package com.experterp.backend.event;

import com.experterp.backend.entity.StockMovement.MovementType;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;

@Getter
public class StockChangedEvent extends ApplicationEvent {
    private final Long productId;
    private final Long warehouseId;
    private final BigDecimal quantity;
    private final MovementType type;
    private final String referenceId;

    public StockChangedEvent(Object source, Long productId, Long warehouseId,
                              BigDecimal quantity, MovementType type, String referenceId) {
        super(source);
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.type = type;
        this.referenceId = referenceId;
    }
}
