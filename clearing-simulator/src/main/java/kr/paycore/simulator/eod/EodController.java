package kr.paycore.simulator.eod;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** EOD 운영 API (docs §5.4 마지막 줄). */
@RestController
@RequestMapping("/simulator/eod")
public class EodController {

    private final EodService eodService;

    public EodController(EodService eodService) {
        this.eodService = eodService;
    }

    /** 당일(또는 지정 업무일자) 처리 내역을 CSV 파일로 생성한다. */
    @PostMapping
    public EodService.EodResult generate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return eodService.generate(date);
    }

    /** recon-batch 입력. 파일 공유 볼륨 없이 HTTP 로 가져갈 수 있게 한다. */
    @GetMapping(value = "/{date}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String download(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return eodService.render(date);
    }
}
