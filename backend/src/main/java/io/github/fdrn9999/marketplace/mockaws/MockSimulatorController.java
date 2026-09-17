package io.github.fdrn9999.marketplace.mockaws;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.fdrn9999.marketplace.mockaws.MockAwsFaults.Api;
import io.github.fdrn9999.marketplace.mockaws.MockAwsFaults.Fault;
import io.github.fdrn9999.marketplace.mockaws.MockAwsFaults.Mode;
import io.github.fdrn9999.marketplace.mockaws.MockSimulatorService.EventRequest;
import io.github.fdrn9999.marketplace.mockaws.MockSimulatorService.EventResult;
import io.github.fdrn9999.marketplace.mockaws.MockSimulatorService.PurchaseRequest;
import io.github.fdrn9999.marketplace.mockaws.MockSimulatorService.PurchaseResult;

/** 데모 전용 시뮬레이터 API. {@code app.demo-mode=false}이면 등록되지 않는다. */
@RestController
@RequestMapping("/mock-aws/_sim")
@ConditionalOnProperty(name = "app.demo-mode", havingValue = "true", matchIfMissing = true)
public class MockSimulatorController {

    public record FaultRequest(Api api, Mode mode, Integer remaining) {
    }

    private final MockSimulatorService simulator;
    private final MockAwsCallLog callLog;
    private final MockAwsFaults faults;

    public MockSimulatorController(MockSimulatorService simulator, MockAwsCallLog callLog, MockAwsFaults faults) {
        this.simulator = simulator;
        this.callLog = callLog;
        this.faults = faults;
    }

    /** AWS Marketplace에서 "구독" 버튼을 누른 것과 같다. 등록 토큰과 Fulfillment URL을 돌려준다. */
    @PostMapping("/purchase")
    public PurchaseResult purchase(@RequestBody(required = false) PurchaseRequest request) {
        return simulator.purchase(request);
    }

    /** 구독 시작 / 계약 갱신 / 해지 이벤트를 발행한다. */
    @PostMapping("/events")
    public EventResult publish(@RequestBody(required = false) EventRequest request) {
        return simulator.publish(request);
    }

    @GetMapping("/calls")
    public List<MockAwsCallLog.Entry> calls(@RequestParam(required = false) String licenseArn,
            @RequestParam(defaultValue = "30") int limit) {
        return callLog.recent(licenseArn, limit);
    }

    @GetMapping("/faults")
    public Map<Api, Fault> faults() {
        return faults.snapshot();
    }

    /** remaining: 적용 횟수(기본 1). -1이면 해제할 때까지 계속 */
    @PutMapping("/faults")
    public Map<Api, Fault> setFault(@RequestBody FaultRequest request) {
        if (request == null || request.api() == null || request.mode() == null) {
            throw MockAwsException.clientError("ValidationException", "api and mode are required.");
        }
        faults.set(request.api(), request.mode(), request.remaining() == null ? 1 : request.remaining());
        return faults.snapshot();
    }
}
