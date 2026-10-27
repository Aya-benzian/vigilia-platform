package com.vigilia.transaction.service;

import com.vigilia.transaction.entity.WalletLedgerEntity;
import com.vigilia.transaction.entity.WalletLedgerEntryType;
import com.vigilia.transaction.exception.InvalidAmountException;
import com.vigilia.transaction.exception.InvalidOperationException;
import com.vigilia.transaction.repository.WalletLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletLedgerServiceImplTest {

    @Mock
    private WalletLedgerRepository walletLedgerRepository;

    @InjectMocks
    private WalletLedgerServiceImpl walletLedgerService;

    private UUID walletId;
    private UUID transactionId;

    @BeforeEach
    void setUp() {
        walletId = UUID.randomUUID();
        transactionId = UUID.randomUUID();
    }

    @Test
    @DisplayName("recordEntry avec enum HOLD persiste une entité immuable valide")
    void recordEntry_withEnumHold_savesAndReturnsEntity() {
        BigDecimal amount = new BigDecimal("50.00");
        BigDecimal balanceAfter = new BigDecimal("150.00");

        when(walletLedgerRepository.save(any(WalletLedgerEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WalletLedgerEntity result = walletLedgerService.recordEntry(
                walletId, transactionId, WalletLedgerEntryType.HOLD, amount, balanceAfter);

        assertThat(result).isNotNull();
        assertThat(result.getWalletId()).isEqualTo(walletId);
        assertThat(result.getTransactionId()).isEqualTo(transactionId);
        assertThat(result.getEntryType()).isEqualTo("HOLD");
        assertThat(result.getAmount()).isEqualByComparingTo(amount);
        assertThat(result.getBalanceAfter()).isEqualByComparingTo(balanceAfter);
        assertThat(result.getCreatedAt()).isNotNull();

        ArgumentCaptor<WalletLedgerEntity> captor = ArgumentCaptor.forClass(WalletLedgerEntity.class);
        verify(walletLedgerRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryType()).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("recordEntry avec walletId null lance InvalidOperationException")
    void recordEntry_nullWalletId_throwsInvalidOperationException() {
        assertThatThrownBy(() -> walletLedgerService.recordEntry(
                null, transactionId, "CREDIT", new BigDecimal("10.00"), new BigDecimal("10.00")))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("walletId ne doit pas être null");

        verifyNoInteractions(walletLedgerRepository);
    }

    @Test
    @DisplayName("recordEntry avec entryType vide lance InvalidOperationException")
    void recordEntry_blankEntryType_throwsInvalidOperationException() {
        assertThatThrownBy(() -> walletLedgerService.recordEntry(
                walletId, transactionId, "   ", new BigDecimal("10.00"), new BigDecimal("10.00")))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("entryType ne doit pas être vide");

        verifyNoInteractions(walletLedgerRepository);
    }

    @Test
    @DisplayName("recordEntry avec montant zéro ou négatif lance InvalidAmountException")
    void recordEntry_zeroOrNegativeAmount_throwsInvalidAmountException() {
        assertThatThrownBy(() -> walletLedgerService.recordEntry(
                walletId, transactionId, "CREDIT", BigDecimal.ZERO, new BigDecimal("10.00")))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("strictement positif");

        assertThatThrownBy(() -> walletLedgerService.recordEntry(
                walletId, transactionId, "CREDIT", new BigDecimal("-5.00"), new BigDecimal("10.00")))
                .isInstanceOf(InvalidAmountException.class);

        verifyNoInteractions(walletLedgerRepository);
    }

    @Test
    @DisplayName("recordEntry avec balanceAfter négatif lance InvalidOperationException")
    void recordEntry_negativeBalanceAfter_throwsInvalidOperationException() {
        assertThatThrownBy(() -> walletLedgerService.recordEntry(
                walletId, transactionId, "DEBIT", new BigDecimal("10.00"), new BigDecimal("-1.00")))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("balanceAfter ne peut pas être négatif");

        verifyNoInteractions(walletLedgerRepository);
    }

    @Test
    @DisplayName("getLedgerEntries retourne la liste ordonnée des écritures")
    void getLedgerEntries_success() {
        WalletLedgerEntity e1 = new WalletLedgerEntity(walletId, transactionId, "CREDIT", new BigDecimal("100.00"), new BigDecimal("100.00"));
        when(walletLedgerRepository.findByWalletIdOrderByCreatedAtDesc(walletId))
                .thenReturn(List.of(e1));

        List<WalletLedgerEntity> entries = walletLedgerService.getLedgerEntries(walletId);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("getLedgerEntries avec pagination retourne une page d'écritures")
    void getLedgerEntries_paginated_success() {
        PageRequest pageRequest = PageRequest.of(0, 10);
        WalletLedgerEntity e1 = new WalletLedgerEntity(walletId, transactionId, "HOLD", new BigDecimal("20.00"), new BigDecimal("80.00"));
        when(walletLedgerRepository.findByWalletIdOrderByCreatedAtDesc(walletId, pageRequest))
                .thenReturn(new PageImpl<>(List.of(e1)));

        Page<WalletLedgerEntity> page = walletLedgerService.getLedgerEntries(walletId, pageRequest);

        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("getEntriesByTransactionId retourne les mouvements d'une transaction")
    void getEntriesByTransactionId_success() {
        WalletLedgerEntity e1 = new WalletLedgerEntity(walletId, transactionId, "HOLD", new BigDecimal("30.00"), new BigDecimal("70.00"));
        when(walletLedgerRepository.findByTransactionId(transactionId))
                .thenReturn(List.of(e1));

        List<WalletLedgerEntity> entries = walletLedgerService.getEntriesByTransactionId(transactionId);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getTransactionId()).isEqualTo(transactionId);
    }
}
