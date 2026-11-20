package com.vigilia.transaction.dto;

public class TransactionStatsResponse {

    // Channel split
    private long totalTransactions;
    private long purchaseCount;
    private long transferCount;
    private double purchasePct;
    private double transferPct;

    // Volume
    private double purchaseVolume;   // SUM(amount) for PURCHASE
    private double transferVolume;   // SUM(amount) for TRANSFER

    // Pass rates (APPROVED / total per type)
    private double purchasePassRate;
    private double transferPassRate;

    // Average transaction amounts
    private double purchaseAvgAmount;
    private double transferAvgAmount;

    public long getTotalTransactions() {
        return totalTransactions;
    }

    public void setTotalTransactions(long totalTransactions) {
        this.totalTransactions = totalTransactions;
    }

    public long getPurchaseCount() {
        return purchaseCount;
    }

    public void setPurchaseCount(long purchaseCount) {
        this.purchaseCount = purchaseCount;
    }

    public long getTransferCount() {
        return transferCount;
    }

    public void setTransferCount(long transferCount) {
        this.transferCount = transferCount;
    }

    public double getPurchasePct() {
        return purchasePct;
    }

    public void setPurchasePct(double purchasePct) {
        this.purchasePct = purchasePct;
    }

    public double getTransferPct() {
        return transferPct;
    }

    public void setTransferPct(double transferPct) {
        this.transferPct = transferPct;
    }

    public double getPurchaseVolume() {
        return purchaseVolume;
    }

    public void setPurchaseVolume(double purchaseVolume) {
        this.purchaseVolume = purchaseVolume;
    }

    public double getTransferVolume() {
        return transferVolume;
    }

    public void setTransferVolume(double transferVolume) {
        this.transferVolume = transferVolume;
    }

    public double getPurchasePassRate() {
        return purchasePassRate;
    }

    public void setPurchasePassRate(double purchasePassRate) {
        this.purchasePassRate = purchasePassRate;
    }

    public double getTransferPassRate() {
        return transferPassRate;
    }

    public void setTransferPassRate(double transferPassRate) {
        this.transferPassRate = transferPassRate;
    }

    public double getPurchaseAvgAmount() {
        return purchaseAvgAmount;
    }

    public void setPurchaseAvgAmount(double purchaseAvgAmount) {
        this.purchaseAvgAmount = purchaseAvgAmount;
    }

    public double getTransferAvgAmount() {
        return transferAvgAmount;
    }

    public void setTransferAvgAmount(double transferAvgAmount) {
        this.transferAvgAmount = transferAvgAmount;
    }
}
