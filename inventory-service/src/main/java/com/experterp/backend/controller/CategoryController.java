package com.experterp.backend.controller;

import com.experterp.backend.entity.Category;
import com.experterp.backend.repository.CategoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryRepository repo;

    public CategoryController(CategoryRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Category> list() {
        return repo.findAll();
    }

    @PostMapping
    public ResponseEntity<Category> create(@RequestBody Category category) {
        if (repo.existsByName(category.getName())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(repo.save(category));
    }
}
