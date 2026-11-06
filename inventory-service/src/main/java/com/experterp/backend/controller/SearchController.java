package com.experterp.backend.controller;

import com.experterp.backend.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The top bar's search over everything the user may open (phase I2). */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public List<SearchService.Hit> search(@RequestParam(defaultValue = "") String q) {
        return searchService.search(q);
    }
}
