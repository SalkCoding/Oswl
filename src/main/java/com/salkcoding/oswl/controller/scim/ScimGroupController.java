package com.salkcoding.oswl.controller.scim;

import com.salkcoding.oswl.controller.spec.ScimGroupControllerSpec;
import com.salkcoding.oswl.dto.scim.ScimError;
import com.salkcoding.oswl.dto.scim.ScimGroup;
import com.salkcoding.oswl.dto.scim.ScimPatchRequest;
import com.salkcoding.oswl.service.scim.ScimProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * SCIM 2.0 Groups resource endpoint.
 *
 * Authentication is performed by {@link com.salkcoding.oswl.web.interceptor.ScimAuthInterceptor}
 * using a dedicated SCIM-scoped API key. Groups are mapped to either Team or RoleTemplate
 * based on {@code oswl.scim.group-mapping}.
 */
@RestController
@RequestMapping("/scim/v2/Groups")
@RequiredArgsConstructor
public class ScimGroupController implements ScimGroupControllerSpec {

    private final ScimProvisioningService scimProvisioningService;

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getGroup(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(scimProvisioningService.getGroup(id));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        }
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> listGroups() {
        return ResponseEntity.ok(scimProvisioningService.listGroups());
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createGroup(@RequestBody ScimGroup request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(scimProvisioningService.createGroup(request));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateGroup(@PathVariable Long id, @RequestBody ScimGroup request) {
        try {
            return ResponseEntity.ok(scimProvisioningService.updateGroup(id, request));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PatchMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> patchGroup(@PathVariable Long id, @RequestBody ScimPatchRequest request) {
        try {
            return ResponseEntity.ok(scimProvisioningService.patchGroup(id, request));
        } catch (NoSuchElementException e) {
            return notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        try {
            scimProvisioningService.deleteGroup(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    private ResponseEntity<ScimError> notFound(String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ScimError.builder()
                        .schemas(List.of("urn:ietf:params:scim:api:messages:2.0:Error"))
                        .status(String.valueOf(HttpStatus.NOT_FOUND.value()))
                        .detail(detail)
                        .build());
    }

    private ResponseEntity<ScimError> badRequest(String detail) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ScimError.builder()
                        .schemas(List.of("urn:ietf:params:scim:api:messages:2.0:Error"))
                        .status(String.valueOf(HttpStatus.BAD_REQUEST.value()))
                        .detail(detail)
                        .build());
    }
}
