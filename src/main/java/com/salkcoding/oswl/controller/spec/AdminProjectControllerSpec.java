package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.AdminProjectRefDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Admin Projects", description = "Lightweight project references for SYSTEM_ADMIN-only operational UIs.")
public interface AdminProjectControllerSpec {

    @Operation(summary = "List active projects (id + name only)",
        description = """
            Returns every non-deleted project as a bare id/name pair, newest first. Exists for
            admin pickers (e.g. the scan-archiving panel) that must not depend on the heavier
            project-card payload. SYSTEM_ADMIN only.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Active projects, newest first",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AdminProjectRefDto.class)))),
        @ApiResponse(responseCode = "403", description = "Not a SYSTEM_ADMIN", content = @Content)
    })
    List<AdminProjectRefDto> listActiveProjects();
}
