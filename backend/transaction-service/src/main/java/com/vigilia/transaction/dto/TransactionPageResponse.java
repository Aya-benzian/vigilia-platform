package com.vigilia.transaction.dto;

import java.util.List;

public class TransactionPageResponse {

    private List<TransactionResponse> content;
    private PaginationMeta meta;

    public TransactionPageResponse() {
    }

    public TransactionPageResponse(List<TransactionResponse> content, PaginationMeta meta) {
        this.content = content;
        this.meta = meta;
    }

    public List<TransactionResponse> getContent() {
        return content;
    }

    public void setContent(List<TransactionResponse> content) {
        this.content = content;
    }

    public PaginationMeta getMeta() {
        return meta;
    }

    public void setMeta(PaginationMeta meta) {
        this.meta = meta;
    }
}
