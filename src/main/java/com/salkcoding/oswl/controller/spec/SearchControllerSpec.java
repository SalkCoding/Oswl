package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.search.GlobalSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Search", description = "Global search across projects, components, and CVE ids.")
public interface SearchControllerSpec {

    @Operation(summary = "Global search",
        description = """
            Unified search backing the keyboard palette. Matches projects by name, components by
            library name, and CVEs by CVE id — all scoped to the projects the current user can
            view (components and CVEs come from the latest completed scan of each accessible
            project). Queries shorter than 2 characters after trimming return empty groups.
            Each group is capped and carries a {@code hasMore} hint.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Grouped, capped search results",
            content = @Content(schema = @Schema(implementation = GlobalSearchResponse.class))),
        @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    GlobalSearchResponse search(
        @Parameter(description = "Search text; minimum 2 characters after trimming")
        @RequestParam(required = false) String q);
}
