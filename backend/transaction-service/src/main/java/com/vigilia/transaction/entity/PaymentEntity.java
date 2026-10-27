package com.vigilia.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Entity for PURCHASE transaction details.
 * Schema: transaction | Table: payments
 */
@Entity
@Table(name = "payments", schema = "transaction")
public class PaymentEntity {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "payment_session_id", length = 30)
    private String paymentSessionId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "product_id", length = 100)
    private String productId;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "merchant_category", length = 50)
    private String merchantCategory;

    @Column(name = "card_brand", length = 20)
    private String cardBrand;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "customer_lat", precision = 9, scale = 6)
    private BigDecimal customerLat;

    @Column(name = "customer_lon", precision = 9, scale = 6)
    private BigDecimal customerLon;

    public PaymentEntity() {
    }

    public PaymentEntity(UUID transactionId, UUID merchantId) {
        this.transactionId = transactionId;
        this.merchantId = merchantId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(UUID transactionId) {
        this.transactionId = transactionId;
    }

    public String getPaymentSessionId() {
        return paymentSessionId;
    }

    public void setPaymentSessionId(String paymentSessionId) {
        this.paymentSessionId = paymentSessionId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getMerchantCategory() {
        return merchantCategory;
    }

    public void setMerchantCategory(String merchantCategory) {
        this.merchantCategory = merchantCategory;
    }

    public String getCardBrand() {
        return cardBrand;
    }

    public void setCardBrand(String cardBrand) {
        this.cardBrand = cardBrand;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public void setCardLast4(String cardLast4) {
        this.cardLast4 = cardLast4;
    }

    public BigDecimal getCustomerLat() {
        return customerLat;
    }

    public void setCustomerLat(BigDecimal customerLat) {
        this.customerLat = customerLat;
    }

    public BigDecimal getCustomerLon() {
        return customerLon;
    }

    public void setCustomerLon(BigDecimal customerLon) {
        this.customerLon = customerLon;
    }
}
