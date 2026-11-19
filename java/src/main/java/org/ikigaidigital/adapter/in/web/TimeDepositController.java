package org.ikigaidigital.adapter.in.web;

import org.ikigaidigital.adapter.in.web.api.TimeDepositsApi;
import org.ikigaidigital.adapter.in.web.model.TimeDepositDto;
import org.ikigaidigital.adapter.in.web.model.WithdrawalDto;
import org.ikigaidigital.application.port.in.GetAllTimeDepositsUseCase;
import org.ikigaidigital.application.port.in.UpdateAllBalancesUseCase;
import org.ikigaidigital.domain.TimeDepositPage;
import org.ikigaidigital.domain.TimeDepositWithWithdrawals;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/** Implements the interface generated from the OpenAPI contract (static/openapi/time-deposits.yaml). */
@RestController
class TimeDepositController implements TimeDepositsApi {

    // Contract: "limit defaults to 100 when only afterId is given"; no parameters -> all deposits.
    private static final int DEFAULT_LIMIT = 100;
    private static final int START = 0; // ids are positive identity values

    private final GetAllTimeDepositsUseCase getAllTimeDeposits;
    private final UpdateAllBalancesUseCase updateAllBalances;

    TimeDepositController(GetAllTimeDepositsUseCase getAllTimeDeposits,
                          UpdateAllBalancesUseCase updateAllBalances) {
        this.getAllTimeDeposits = getAllTimeDeposits;
        this.updateAllBalances = updateAllBalances;
    }

    @Override
    public ResponseEntity<List<TimeDepositDto>> getTimeDeposits(Integer limit, Integer afterId) {
        if (limit == null && afterId == null) {
            return ResponseEntity.ok(toDtos(getAllTimeDeposits.getAll()));
        }
        int pageSize = limit != null ? limit : DEFAULT_LIMIT;
        TimeDepositPage page = getAllTimeDeposits.getPage(afterId != null ? afterId : START, pageSize);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (page.hasNext()) {
            int lastId = page.items().get(page.items().size() - 1).id();
            response.header(HttpHeaders.LINK, nextPageLink(lastId, pageSize));
        }
        return response.body(toDtos(page.items()));
    }

    @Override
    public ResponseEntity<Void> updateBalances() {
        updateAllBalances.updateAllBalances();
        return ResponseEntity.noContent().build();
    }

    /**
     * RFC 8288 link to the next page as a path-absolute reference ("/time-deposits?afterId=..&limit=.."):
     * no scheme or host taken from request headers (correct behind a reverse proxy, no Host-header
     * injection), and no other request parameter is copied (or re-encoded).
     */
    private static String nextPageLink(int lastId, int pageSize) {
        String reference = UriComponentsBuilder.fromPath(PATH_GET_TIME_DEPOSITS)
                .queryParam("afterId", lastId)
                .queryParam("limit", pageSize)
                .toUriString();
        return "<" + reference + ">; rel=\"next\"";
    }

    private static List<TimeDepositDto> toDtos(List<TimeDepositWithWithdrawals> deposits) {
        return deposits.stream()
                .map(deposit -> new TimeDepositDto(
                        deposit.id(), deposit.planType(), deposit.balance(), deposit.days(),
                        deposit.withdrawals().stream()
                                .map(w -> new WithdrawalDto(w.id(), w.amount(), w.date()))
                                .toList()))
                .toList();
    }
}
