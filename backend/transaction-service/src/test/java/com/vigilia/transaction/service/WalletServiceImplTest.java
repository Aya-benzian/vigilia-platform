package com.vigilia.transaction.service;

import com.vigilia.transaction.dto.WalletResponse;
import com.vigilia.transaction.entity.WalletEntity;
import com.vigilia.transaction.entity.WalletLedgerEntryType;
import com.vigilia.transaction.exception.InsufficientFundsException;
import com.vigilia.transaction.exception.InvalidAmountException;
import com.vigilia.transaction.exception.InvalidOperationException;
import com.vigilia.transaction.exception.ResourceNotFoundException;
import com.vigilia.transaction.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceImplTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletLedgerService walletLedgerService;

    @InjectMocks
    private WalletServiceImpl walletService;

    private UUID walletId;
    private UUID customerId;
    private UUID transactionId;
    private WalletEntity testWallet;

    @BeforeEach
    void setUp() {
        walletId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        transactionId = UUID.randomUUID();

        testWallet = new WalletEntity(customerId, "FR7630006000011234567890189", "EUR", new BigDecimal("100.00"));
        testWallet.setId(walletId);
        testWallet.setHeldBalance(new BigDecimal("0.00"));
    }

    // -------------------------------------------------------------------------
    // 1. Consultation d'un portefeuille existant
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("getWallet retourne le DTO correctement mappé pour un portefeuille existant")
    void getWallet_existing_returnsResponse() {
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(testWallet));

        WalletResponse response = walletService.getWallet(walletId);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(walletId);
        assertThat(response.getCustomerId()).isEqualTo(customerId);
        assertThat(response.getAccountNumber()).isEqualTo("FR7630006000011234567890189");
        assertThat(response.getCurrency()).isEqualTo("EUR");
        assertThat(response.getAvailableBalance()).isEqualByComparingTo("100.00");
        assertThat(response.getHeldBalance()).isEqualByComparingTo("0.00");
        assertThat(response.getTotalBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("getWallet pour un identifiant inconnu lance ResourceNotFoundException")
    void getWallet_notFound_throwsResourceNotFoundException() {
        when(walletRepository.findById(walletId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> walletService.getWallet(walletId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Portefeuille introuvable");
    }

    @Test
    @DisplayName("getWalletByCustomerId retourne le portefeuille du client")
    void getWalletByCustomerId_existing_returnsResponse() {
        when(walletRepository.findByCustomerId(customerId)).thenReturn(Optional.of(testWallet));

        WalletResponse response = walletService.getWalletByCustomerId(customerId);

        assertThat(response).isNotNull();
        assertThat(response.getCustomerId()).isEqualTo(customerId);
    }

    // -------------------------------------------------------------------------
    // 2. Création de portefeuille
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("createWallet avec solde initial persiste le portefeuille et écrit le CREDIT initial au ledger")
    void createWallet_withInitialBalance_createsAndWritesLedger() {
        when(walletRepository.existsByCustomerId(customerId)).thenReturn(false);
        when(walletRepository.existsByAccountNumber("FR7630006000011234567890189")).thenReturn(false);
        when(walletRepository.save(any(WalletEntity.class))).thenAnswer(inv -> {
            WalletEntity w = inv.getArgument(0);
            w.setId(walletId);
            return w;
        });

        WalletResponse response = walletService.createWallet(
                customerId, "FR7630006000011234567890189", "EUR", new BigDecimal("50.00"));

        assertThat(response).isNotNull();
        assertThat(response.getAvailableBalance()).isEqualByComparingTo("50.00");
        verify(walletLedgerService).recordEntry(
                eq(walletId), eq(null), eq(WalletLedgerEntryType.CREDIT), eq(new BigDecimal("50.00")), eq(new BigDecimal("50.00")));
    }

    @Test
    @DisplayName("createWallet rejette un client déjà existant")
    void createWallet_duplicateCustomer_throwsInvalidOperationException() {
        when(walletRepository.existsByCustomerId(customerId)).thenReturn(true);

        assertThatThrownBy(() -> walletService.createWallet(
                customerId, "FR7630006000011234567890189", "EUR", BigDecimal.ZERO))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("existe déjà");

        verify(walletRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // 3. Réservation de fonds avec solde suffisant (HOLD)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("holdFunds avec solde suffisant déduit le disponible, incrémente le retenu et écrit au ledger")
    void holdFunds_sufficientBalance_reservesAndWritesLedger() {
        BigDecimal holdAmount = new BigDecimal("40.00");
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(testWallet));
        when(walletRepository.save(any(WalletEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.holdFunds(walletId, transactionId, holdAmount);

        assertThat(response.getAvailableBalance()).isEqualByComparingTo("60.00");
        assertThat(response.getHeldBalance()).isEqualByComparingTo("40.00");
        assertThat(response.getTotalBalance()).isEqualByComparingTo("100.00");

        verify(walletRepository).findByIdForUpdate(walletId);
        verify(walletRepository).save(testWallet);
        verify(walletLedgerService).recordEntry(
                walletId, transactionId, WalletLedgerEntryType.HOLD, holdAmount, new BigDecimal("60.00"));
    }

    // -------------------------------------------------------------------------
    // 4. Refus de réservation lorsque le solde est insuffisant
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("holdFunds avec solde insuffisant lance InsufficientFundsException sans modifier le portefeuille ni le ledger")
    void holdFunds_insufficientBalance_refusesAndDoesNotModifyWalletOrLedger() {
        BigDecimal holdAmount = new BigDecimal("150.00");
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(testWallet));

        assertThatThrownBy(() -> walletService.holdFunds(walletId, transactionId, holdAmount))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("Solde insuffisant");

        assertThat(testWallet.getAvailableBalance()).isEqualByComparingTo("100.00");
        assertThat(testWallet.getHeldBalance()).isEqualByComparingTo("0.00");

        verify(walletRepository, never()).save(any());
        verify(walletLedgerService, never()).recordEntry(any(), any(), any(WalletLedgerEntryType.class), any(), any());
    }

    // -------------------------------------------------------------------------
    // 5. Libération d'une réservation (RELEASE)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("releaseFunds restitue les fonds retenus au disponible et écrit au ledger")
    void releaseFunds_validHeldAmount_releasesAndWritesLedger() {
        testWallet.setAvailableBalance(new BigDecimal("60.00"));
        testWallet.setHeldBalance(new BigDecimal("40.00"));

        BigDecimal releaseAmount = new BigDecimal("40.00");
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(testWallet));
        when(walletRepository.save(any(WalletEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.releaseFunds(walletId, transactionId, releaseAmount);

        assertThat(response.getAvailableBalance()).isEqualByComparingTo("100.00");
        assertThat(response.getHeldBalance()).isEqualByComparingTo("0.00");

        verify(walletRepository).findByIdForUpdate(walletId);
        verify(walletRepository).save(testWallet);
        verify(walletLedgerService).recordEntry(
                walletId, transactionId, WalletLedgerEntryType.RELEASE, releaseAmount, new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("releaseFunds avec montant supérieur aux fonds retenus lance InvalidOperationException")
    void releaseFunds_exceedsHeldBalance_throwsInvalidOperationException() {
        testWallet.setHeldBalance(new BigDecimal("20.00"));
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(testWallet));

        assertThatThrownBy(() -> walletService.releaseFunds(walletId, transactionId, new BigDecimal("50.00")))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Fonds réservés insuffisants");

        verify(walletRepository, never()).save(any());
        verify(walletLedgerService, never()).recordEntry(any(), any(), any(WalletLedgerEntryType.class), any(), any());
    }

    // -------------------------------------------------------------------------
    // 6. Débit définitif d'une réservation (SETTLEMENT)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("settleHold consomme les fonds retenus sans toucher au disponible et écrit au ledger")
    void settleHold_validHeldAmount_consumesHeldAndWritesLedger() {
        testWallet.setAvailableBalance(new BigDecimal("60.00"));
        testWallet.setHeldBalance(new BigDecimal("40.00"));

        BigDecimal settleAmount = new BigDecimal("40.00");
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(testWallet));
        when(walletRepository.save(any(WalletEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.settleHold(walletId, transactionId, settleAmount);

        assertThat(response.getAvailableBalance()).isEqualByComparingTo("60.00");
        assertThat(response.getHeldBalance()).isEqualByComparingTo("0.00");
        assertThat(response.getTotalBalance()).isEqualByComparingTo("60.00");

        verify(walletRepository).findByIdForUpdate(walletId);
        verify(walletRepository).save(testWallet);
        verify(walletLedgerService).recordEntry(
                walletId, transactionId, WalletLedgerEntryType.SETTLEMENT, settleAmount, new BigDecimal("60.00"));
    }

    // -------------------------------------------------------------------------
    // 7. Débit direct (DEBIT)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("debitFunds déduit directement le disponible et écrit au ledger")
    void debitFunds_sufficientBalance_deductsAvailableAndWritesLedger() {
        BigDecimal debitAmount = new BigDecimal("30.00");
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(testWallet));
        when(walletRepository.save(any(WalletEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.debitFunds(walletId, transactionId, debitAmount);

        assertThat(response.getAvailableBalance()).isEqualByComparingTo("70.00");
        assertThat(response.getHeldBalance()).isEqualByComparingTo("0.00");

        verify(walletRepository).findByIdForUpdate(walletId);
        verify(walletRepository).save(testWallet);
        verify(walletLedgerService).recordEntry(
                walletId, transactionId, WalletLedgerEntryType.DEBIT, debitAmount, new BigDecimal("70.00"));
    }

    @Test
    @DisplayName("debitFunds avec solde disponible insuffisant lance InsufficientFundsException")
    void debitFunds_insufficientBalance_throwsInsufficientFundsException() {
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(testWallet));

        assertThatThrownBy(() -> walletService.debitFunds(walletId, transactionId, new BigDecimal("200.00")))
                .isInstanceOf(InsufficientFundsException.class);

        verify(walletRepository, never()).save(any());
        verify(walletLedgerService, never()).recordEntry(any(), any(), any(WalletLedgerEntryType.class), any(), any());
    }

    // -------------------------------------------------------------------------
    // 8. Crédit du portefeuille (CREDIT)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("creditFunds augmente le disponible et écrit au ledger")
    void creditFunds_validAmount_addsAvailableAndWritesLedger() {
        BigDecimal creditAmount = new BigDecimal("75.50");
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(testWallet));
        when(walletRepository.save(any(WalletEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.creditFunds(walletId, transactionId, creditAmount);

        assertThat(response.getAvailableBalance()).isEqualByComparingTo("175.50");
        assertThat(response.getHeldBalance()).isEqualByComparingTo("0.00");

        verify(walletRepository).findByIdForUpdate(walletId);
        verify(walletRepository).save(testWallet);
        verify(walletLedgerService).recordEntry(
                walletId, transactionId, WalletLedgerEntryType.CREDIT, creditAmount, new BigDecimal("175.50"));
    }

    // -------------------------------------------------------------------------
    // 9. Rollback si l'écriture du ledger échoue
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("holdFunds propage l'exception si le ledger échoue, déclenchant le rollback transactionnel")
    void holdFunds_whenLedgerFails_propagatesExceptionForRollback() {
        BigDecimal holdAmount = new BigDecimal("30.00");
        when(walletRepository.findByIdForUpdate(walletId)).thenReturn(Optional.of(testWallet));
        when(walletRepository.save(any(WalletEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(walletLedgerService.recordEntry(any(), any(), any(WalletLedgerEntryType.class), any(), any()))
                .thenThrow(new RuntimeException("Erreur d'insertion dans wallet_ledger (PostgreSQL I/O error)"));

        assertThatThrownBy(() -> walletService.holdFunds(walletId, transactionId, holdAmount))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Erreur d'insertion dans wallet_ledger");

        verify(walletLedgerService).recordEntry(
                walletId, transactionId, WalletLedgerEntryType.HOLD, holdAmount, new BigDecimal("70.00"));
    }

    // -------------------------------------------------------------------------
    // 10. Prévention des montants invalides et soldes négatifs
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("holdFunds avec montant nul, négatif ou zéro lance InvalidAmountException")
    void holdFunds_invalidAmounts_throwInvalidAmountException() {
        assertThatThrownBy(() -> walletService.holdFunds(walletId, transactionId, null))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("ne peut pas être null");

        assertThatThrownBy(() -> walletService.holdFunds(walletId, transactionId, BigDecimal.ZERO))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("strictement positif");

        assertThatThrownBy(() -> walletService.holdFunds(walletId, transactionId, new BigDecimal("-10.00")))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("strictement positif");

        verifyNoInteractions(walletRepository);
        verifyNoInteractions(walletLedgerService);
    }

    @Test
    @DisplayName("holdFunds avec plus de 2 décimales lance InvalidAmountException")
    void holdFunds_invalidScale_throwsInvalidAmountException() {
        assertThatThrownBy(() -> walletService.holdFunds(walletId, transactionId, new BigDecimal("10.555")))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("2 décimales");

        verifyNoInteractions(walletRepository);
        verifyNoInteractions(walletLedgerService);
    }

    @Test
    @DisplayName("creditFunds avec montant négatif lance InvalidAmountException")
    void creditFunds_negativeAmount_throwsInvalidAmountException() {
        assertThatThrownBy(() -> walletService.creditFunds(walletId, transactionId, new BigDecimal("-50.00")))
                .isInstanceOf(InvalidAmountException.class);

        verifyNoInteractions(walletRepository);
        verifyNoInteractions(walletLedgerService);
    }
}
