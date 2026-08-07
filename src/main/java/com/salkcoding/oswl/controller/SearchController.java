package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.SearchControllerSpec;
import com.salkcoding.oswl.dto.search.GlobalSearchResponse;
import com.salkcoding.oswl.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController implements SearchControllerSpec {

    private final SearchService searchService;

    @GetMapping
    public GlobalSearchResponse search(@RequestParam(required = false) String q) {
        return searchService.search(q);
    }
}
