package com.aegis.policy.api;

import com.aegis.policy.api.dto.BindPolicyRequest;
import com.aegis.policy.api.dto.CancelPolicyRequest;
import com.aegis.policy.api.dto.CoverageDto;
import com.aegis.policy.api.dto.CoverageVerificationResponse;
import com.aegis.policy.api.dto.EndorsePolicyRequest;
import com.aegis.policy.api.dto.PolicyResponse;
import com.aegis.policy.api.dto.PolicyVersionResponse;
import com.aegis.policy.application.BindPolicyCommand;
import com.aegis.policy.application.CancelPolicyCommand;
import com.aegis.policy.application.CoverageCommand;
import com.aegis.policy.application.EndorsePolicyCommand;
import com.aegis.policy.application.PolicyApplicationService;
import com.aegis.policy.domain.Policy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
@Tag(name = "Policies", description = "Binding, endorsement, cancellation and as-of-date coverage resolution")
public class PolicyController {

    private final PolicyApplicationService policies;
    private final Clock clock;

    public PolicyController(PolicyApplicationService policies, Clock clock) {
        this.policies = policies;
        this.clock = clock;
    }

    @PostMapping
    @Operation(
            summary = "Bind a new policy",
            description = "Creates version 1. The policy number must not already exist.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Bound"),
        @ApiResponse(responseCode = "400", description = "Request failed validation"),
        @ApiResponse(responseCode = "409", description = "Policy number already in use"),
    })
    public ResponseEntity<PolicyResponse> bind(@Valid @RequestBody BindPolicyRequest request) {
        Policy policy = policies.bind(new BindPolicyCommand(
                request.policyNumber(),
                request.productCode(),
                request.inceptionDate(),
                request.expiryDate(),
                request.insuredName(),
                request.territory(),
                toCommands(request.coverages())));

        return ResponseEntity.created(URI.create("/api/v1/policies/" + policy.policyNumber()))
                .body(PolicyResponse.from(policy));
    }

    @GetMapping("/{policyNumber}")
    @Operation(summary = "Resolve the version in force on a date", description = """
                    Answers "what were the terms on asOf, as we understood them at asAt".
                    Omit asOf for today and asAt for the current understanding. Supplying asAt
                    reconstructs a past decision: it deliberately hides anything recorded since,
                    including backdated endorsements.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The version in force on that date"),
        @ApiResponse(responseCode = "404", description = "No such policy, or no version in force on that date"),
    })
    public PolicyVersionResponse resolveAsOf(
            @PathVariable String policyNumber,
            @Parameter(description = "Valid-time date. For a claim, the loss date. Defaults to today.")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate asOf,
            @Parameter(description = "Transaction-time instant. Defaults to now.") @RequestParam(required = false)
                    Instant asAt) {

        LocalDate validDate = asOf == null ? LocalDate.now(clock) : asOf;
        return policies.resolveVersion(policyNumber, validDate, asAt)
                .map(PolicyVersionResponse::from)
                .orElseThrow(() -> new NoVersionInForceException(policyNumber, validDate));
    }

    @GetMapping("/{policyNumber}/versions")
    @Operation(summary = "The full audit trail", description = """
                    Every version ever recorded, superseded ones included, ordered by when it was
                    recorded. This is the regulator's view: it shows not just what the policy says
                    now but everything the system has ever believed it said, and when that changed.
                    """)
    public List<PolicyVersionResponse> history(@PathVariable String policyNumber) {
        return policies.history(policyNumber).stream()
                .map(PolicyVersionResponse::from)
                .toList();
    }

    @PostMapping("/{policyNumber}/endorsements")
    @Operation(
            summary = "Endorse a policy",
            description = "Records a mid-term change. effectiveFrom may be in the past.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Endorsed"),
        @ApiResponse(responseCode = "404", description = "No such policy"),
        @ApiResponse(responseCode = "409", description = "Concurrent modification"),
        @ApiResponse(responseCode = "422", description = "Policy was not in force on the effective date"),
    })
    public PolicyResponse endorse(@PathVariable String policyNumber, @Valid @RequestBody EndorsePolicyRequest request) {
        Policy policy = policies.endorse(
                policyNumber,
                new EndorsePolicyCommand(
                        request.effectiveFrom(),
                        request.insuredName(),
                        request.territory(),
                        request.changeReason(),
                        toCommands(request.coverages())));
        return PolicyResponse.from(policy);
    }

    @PostMapping("/{policyNumber}/cancellation")
    @Operation(summary = "Cancel a policy", description = "Terminal. Takes the policy off risk from effectiveFrom.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cancelled"),
        @ApiResponse(responseCode = "404", description = "No such policy"),
        @ApiResponse(responseCode = "422", description = "Policy was not in force on the effective date"),
    })
    public PolicyResponse cancel(@PathVariable String policyNumber, @Valid @RequestBody CancelPolicyRequest request) {
        Policy policy =
                policies.cancel(policyNumber, new CancelPolicyCommand(request.effectiveFrom(), request.reason()));
        return PolicyResponse.from(policy);
    }

    @GetMapping("/{policyNumber}/coverage-verification")
    @Operation(summary = "Verify coverage as of a loss date", description = """
                    The call claims-service makes at first notice of loss. Returns 200 whether or
                    not the loss is covered - "not covered" is an answer, not a failure, and the
                    caller must be able to tell it apart from this service being unavailable.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "The coverage position on that date"),
        @ApiResponse(responseCode = "404", description = "No such policy"),
    })
    public CoverageVerificationResponse verifyCoverage(
            @PathVariable String policyNumber,
            @Parameter(description = "The loss date.", required = true)
                    @RequestParam
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate lossDate,
            @Parameter(description = "Coverage code to check, e.g. COLLISION.", required = true) @RequestParam
                    String coverageCode,
            @Parameter(description = "Transaction-time instant. Defaults to now.") @RequestParam(required = false)
                    Instant asAt) {

        Instant asAtInstant = asAt == null ? clock.instant() : asAt;
        return policies.resolveVersion(policyNumber, lossDate, asAtInstant)
                .map(version -> CoverageVerificationResponse.from(version, coverageCode, lossDate, asAtInstant))
                .orElseGet(() -> CoverageVerificationResponse.noPolicyVersion(
                        policyNumber, lossDate, asAtInstant, coverageCode));
    }

    private static List<CoverageCommand> toCommands(List<CoverageDto> coverages) {
        return coverages.stream()
                .map(c -> new CoverageCommand(c.coverageCode(), c.limit(), c.deductible(), c.exclusions()))
                .toList();
    }
}
