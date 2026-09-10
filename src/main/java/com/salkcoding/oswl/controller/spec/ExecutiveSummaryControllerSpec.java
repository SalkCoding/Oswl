package com.salkcoding.oswl.controller.spec;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.ui.Model;
public interface ExecutiveSummaryControllerSpec {
    @Operation(summary = "Printable organization risk and action summary")
    String summary(Model model);
}
