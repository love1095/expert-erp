package com.experterp.backend.service;

import com.experterp.backend.dto.WarehouseRequest;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.exception.DuplicateResourceException;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;
    private final SubscriptionService subscriptionService;

    public List<Warehouse> findAll() {
        return warehouseRepository.findAll();
    }

    public Page<Warehouse> findAll(int page, int size) {
        return warehouseRepository.findAll(PageRequest.of(page, size, Sort.by("name")));
    }

    public Warehouse findById(Long id) {
        return warehouseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + id));
    }

    public Warehouse create(WarehouseRequest req) {
        subscriptionService.checkWarehouseLimit(TenantContext.get());
        if (warehouseRepository.existsByName(req.getName())) {
            throw new DuplicateResourceException("Warehouse already exists: " + req.getName());
        }
        Warehouse w = new Warehouse();
        w.setName(req.getName());
        w.setLocation(req.getLocation());
        return warehouseRepository.save(w);
    }

    public Warehouse update(Long id, WarehouseRequest req) {
        Warehouse w = findById(id);
        warehouseRepository.findByName(req.getName()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new DuplicateResourceException("Warehouse name already in use: " + req.getName());
            }
        });
        w.setName(req.getName());
        w.setLocation(req.getLocation());
        return warehouseRepository.save(w);
    }

    public void delete(Long id) {
        findById(id);
        warehouseRepository.deleteById(id);
    }
}
