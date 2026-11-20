package com.vigilia.transaction.controller;

import com.vigilia.transaction.dto.ApiResponse;
import com.vigilia.transaction.dto.TransactionStatsResponse;
import com.vigilia.transaction.repository.TransactionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/transactions/stats")
public class TransactionStatsController {

    private final TransactionRepository repo;

    public TransactionStatsController(TransactionRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<TransactionStatsResponse>> getStats() {
        TransactionStatsResponse stats = new TransactionStatsResponse();

        long total = repo.count();
        long purchases = repo.countByTransactionType("PURCHASE");
        long transfers = repo.countByTransactionType("TRANSFER");

        stats.setTotalTransactions(total);
        stats.setPurchaseCount(purchases);
        stats.setTransferCount(transfers);

        if (total > 0) {
            stats.setPurchasePct(round2((double) purchases / total * 100));
            stats.setTransferPct(round2((double) transfers / total * 100));
        }

        BigDecimal purchaseVol = repo.sumAmountByType("PURCHASE");
        BigDecimal transferVol = repo.sumAmountByType("TRANSFER");
        stats.setPurchaseVolume(purchaseVol.doubleValue());
        stats.setTransferVolume(transferVol.doubleValue());

        BigDecimal purchaseAvg = repo.avgAmountByType("PURCHASE");
        BigDecimal transferAvg = repo.avgAmountByType("TRANSFER");
        stats.setPurchaseAvgAmount(round2(purchaseAvg.doubleValue()));
        stats.setTransferAvgAmount(round2(transferAvg.doubleValue()));

        // Pass rates
        long purchaseApproved = repo.countApprovedByType("PURCHASE");
        long transferApproved = repo.countApprovedByType("TRANSFER");
        stats.setPurchasePassRate(purchases > 0 ? round2((double) purchaseApproved / purchases * 100) : 0);
        stats.setTransferPassRate(transfers > 0 ? round2((double) transferApproved / transfers * 100) : 0);

        return ResponseEntity.ok(ApiResponse.of(stats));
    }

    private double round2(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
