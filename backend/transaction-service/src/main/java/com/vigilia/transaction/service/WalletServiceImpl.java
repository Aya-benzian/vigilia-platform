package com.vigilia.transaction.service;

import com.vigilia.transaction.dto.WalletResponse;
import com.vigilia.transaction.entity.WalletEntity;
import com.vigilia.transaction.entity.WalletLedgerEntryType;
import com.vigilia.transaction.exception.InsufficientFundsException;
import com.vigilia.transaction.exception.InvalidAmountException;
import com.vigilia.transaction.exception.InvalidOperationException;
import com.vigilia.transaction.exception.ResourceNotFoundException;
import com.vigilia.transaction.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Implementation of WalletService orchestrating concurrent-safe wallet balance operations
 * with pessimistic locking and double-entry ledger tracking.
 */
@Service
@Transactional(readOnly = true)
public class WalletServiceImpl implements WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletServiceImpl.class);
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
    private static final Map<String, BigDecimal> USD_RATES = Map.of(
            "USD", BigDecimal.ONE,
            "EUR", new BigDecimal("0.9259"),
            "MAD", new BigDecimal("10.0000"),
            "GBP", new BigDecimal("0.7937")
    );

    private final WalletRepository walletRepository;
    private final WalletLedgerService walletLedgerService;

    public WalletServiceImpl(WalletRepository walletRepository, WalletLedgerService walletLedgerService) {
        this.walletRepository = walletRepository;
        this.walletLedgerService = walletLedgerService;
    }

    @Override
    public WalletResponse getWallet(UUID walletId) {
        return toResponse(getWalletEntity(walletId));
    }

    @Override
    public WalletResponse getWalletByCustomerId(UUID customerId) {
        return toResponse(getWalletEntityByCustomerId(customerId));
    }

    @Override
    public WalletEntity getWalletEntity(UUID walletId) {
        if (walletId == null) {
            throw new InvalidOperationException("walletId ne doit pas être null");
        }
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Portefeuille introuvable pour l'identifiant: " + walletId));
    }

    @Override
    @Transactional
    public WalletEntity getWalletEntityByCustomerId(UUID customerId) {
        if (customerId == null) {
            throw new InvalidOperationException("customerId ne doit pas être null");
        }
        return walletRepository.findByCustomerId(customerId)
                .orElseGet(() -> autoProvisionWallet(customerId));
    }

    private WalletEntity autoProvisionWallet(UUID customerId) {
        String cleanUuid = customerId.toString().replace("-", "").toUpperCase();
        String part1 = cleanUuid.substring(0, 4);
        String part2 = cleanUuid.substring(4, 8);
        String part3 = cleanUuid.substring(8, 12);
        String accountNumber = "VGL-FR-" + part1 + "-" + part2 + "-" + part3;

        if (walletRepository.findByAccountNumber(accountNumber).isPresent()) {
            accountNumber = "VGL-FR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        }

        WalletEntity wallet = new WalletEntity(customerId, accountNumber, "USD", BigDecimal.ZERO);
        WalletEntity saved = walletRepository.save(wallet);
        log.info("Auto-provisioned fresh 0.00 balance wallet for customer [customerId={}, accountNumber={}]", customerId, accountNumber);
        return saved;
    }

    @Override
    @Transactional
    public WalletResponse createWallet(UUID customerId, String accountNumber, String currency, BigDecimal initialBalance) {
        if (customerId == null) {
            throw new InvalidOperationException("customerId ne doit pas être null");
        }
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new InvalidOperationException("accountNumber ne doit pas être vide");
        }
        if (currency == null || !CURRENCY_PATTERN.matcher(currency.trim()).matches()) {
            throw new InvalidOperationException("currency doit être un code ISO 4217 valide de 3 lettres majuscules (ex: EUR, USD)");
        }

        if (walletRepository.existsByCustomerId(customerId)) {
            throw new InvalidOperationException("Un portefeuille existe déjà pour le client: " + customerId);
        }
        if (walletRepository.existsByAccountNumber(accountNumber.trim())) {
            throw new InvalidOperationException("Un portefeuille existe déjà avec ce numéro de compte: " + accountNumber);
        }

        BigDecimal balance = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (initialBalance != null) {
            if (initialBalance.compareTo(BigDecimal.ZERO) < 0) {
                throw new InvalidAmountException("Le solde initial ne peut pas être négatif");
            }
            balance = initialBalance.setScale(2, RoundingMode.HALF_UP);
        }

        WalletEntity wallet = new WalletEntity(customerId, accountNumber.trim(), currency.trim().toUpperCase(), balance);
        WalletEntity saved = walletRepository.save(wallet);

        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            walletLedgerService.recordEntry(saved.getId(), null, WalletLedgerEntryType.CREDIT, balance, balance);
        }

        log.info("Portefeuille créé [walletId={}, customerId={}, currency={}, initialBalance={}]",
                saved.getId(), customerId, saved.getCurrency(), balance);

        return toResponse(saved);
    }

    @Override
    @Transactional
    public WalletResponse holdFunds(UUID walletId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadForUpdate(walletId);

        BigDecimal currentAvailable = wallet.getAvailableBalance();
        if (currentAvailable.compareTo(amount) < 0) {
            throw new InsufficientFundsException(currentAvailable, amount);
        }

        BigDecimal newAvailable = currentAvailable.subtract(amount);
        BigDecimal newHeld = wallet.getHeldBalance().add(amount);

        // Invariant guard against negative balances
        if (newAvailable.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientFundsException(currentAvailable, amount);
        }

        wallet.setAvailableBalance(newAvailable);
        wallet.setHeldBalance(newHeld);
        wallet.setUpdatedAt(Instant.now());
        WalletEntity updated = walletRepository.save(wallet);

        walletLedgerService.recordEntry(updated.getId(), transactionId, WalletLedgerEntryType.HOLD, amount, newAvailable);

        log.info("Fonds réservés (HOLD) [walletId={}, amount={}, newAvailable={}, newHeld={}, transactionId={}]",
                updated.getId(), amount, newAvailable, newHeld, transactionId);

        return toResponse(updated);
    }

    @Override
    @Transactional
    public WalletResponse holdFundsByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadByCustomerIdForUpdate(customerId);
        return holdFunds(wallet.getId(), transactionId, amount);
    }

    @Override
    @Transactional
    public WalletResponse releaseFunds(UUID walletId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadForUpdate(walletId);

        BigDecimal currentHeld = wallet.getHeldBalance();
        if (currentHeld.compareTo(amount) < 0) {
            throw new InvalidOperationException(String.format(
                    "Fonds réservés insuffisants pour exécuter la libération : réservé=%s, demandé=%s",
                    currentHeld, amount));
        }

        BigDecimal newHeld = currentHeld.subtract(amount);
        BigDecimal newAvailable = wallet.getAvailableBalance().add(amount);

        // Invariant guard against negative held balance
        if (newHeld.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidOperationException("Le solde retenu résultant ne peut pas être négatif");
        }

        wallet.setHeldBalance(newHeld);
        wallet.setAvailableBalance(newAvailable);
        wallet.setUpdatedAt(Instant.now());
        WalletEntity updated = walletRepository.save(wallet);

        walletLedgerService.recordEntry(updated.getId(), transactionId, WalletLedgerEntryType.RELEASE, amount, newAvailable);

        log.info("Fonds libérés (RELEASE) [walletId={}, amount={}, newAvailable={}, newHeld={}, transactionId={}]",
                updated.getId(), amount, newAvailable, newHeld, transactionId);

        return toResponse(updated);
    }

    @Override
    @Transactional
    public WalletResponse releaseFundsByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadByCustomerIdForUpdate(customerId);
        return releaseFunds(wallet.getId(), transactionId, amount);
    }

    @Override
    @Transactional
    public WalletResponse settleHold(UUID walletId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadForUpdate(walletId);

        BigDecimal currentHeld = wallet.getHeldBalance();
        if (currentHeld.compareTo(amount) < 0) {
            throw new InvalidOperationException(String.format(
                    "Fonds réservés insuffisants pour exécuter le débit définitif : réservé=%s, requis=%s",
                    currentHeld, amount));
        }

        BigDecimal newHeld = currentHeld.subtract(amount);
        if (newHeld.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidOperationException("Le solde retenu résultant ne peut pas être négatif");
        }

        wallet.setHeldBalance(newHeld);
        wallet.setUpdatedAt(Instant.now());
        WalletEntity updated = walletRepository.save(wallet);

        // Available balance is unchanged during settlement because it was already deducted during HOLD
        walletLedgerService.recordEntry(updated.getId(), transactionId, WalletLedgerEntryType.SETTLEMENT, amount, updated.getAvailableBalance());

        log.info("Débit définitif de réservation (SETTLEMENT) [walletId={}, amount={}, heldRemaining={}, transactionId={}]",
                updated.getId(), amount, newHeld, transactionId);

        return toResponse(updated);
    }

    @Override
    @Transactional
    public WalletResponse settleHoldByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadByCustomerIdForUpdate(customerId);
        return settleHold(wallet.getId(), transactionId, amount);
    }

    @Override
    @Transactional
    public WalletResponse debitFunds(UUID walletId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadForUpdate(walletId);

        BigDecimal currentAvailable = wallet.getAvailableBalance();
        if (currentAvailable.compareTo(amount) < 0) {
            throw new InsufficientFundsException(currentAvailable, amount);
        }

        BigDecimal newAvailable = currentAvailable.subtract(amount);
        if (newAvailable.compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientFundsException(currentAvailable, amount);
        }

        wallet.setAvailableBalance(newAvailable);
        wallet.setUpdatedAt(Instant.now());
        WalletEntity updated = walletRepository.save(wallet);

        walletLedgerService.recordEntry(updated.getId(), transactionId, WalletLedgerEntryType.DEBIT, amount, newAvailable);

        log.info("Débit direct (DEBIT) [walletId={}, amount={}, newAvailable={}, transactionId={}]",
                updated.getId(), amount, newAvailable, transactionId);

        return toResponse(updated);
    }

    @Override
    @Transactional
    public WalletResponse debitFundsByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadByCustomerIdForUpdate(customerId);
        return debitFunds(wallet.getId(), transactionId, amount);
    }

    @Override
    @Transactional
    public WalletResponse creditFunds(UUID walletId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadForUpdate(walletId);

        BigDecimal newAvailable = wallet.getAvailableBalance().add(amount);
        wallet.setAvailableBalance(newAvailable);
        wallet.setUpdatedAt(Instant.now());
        WalletEntity updated = walletRepository.save(wallet);

        walletLedgerService.recordEntry(updated.getId(), transactionId, WalletLedgerEntryType.CREDIT, amount, newAvailable);

        log.info("Fonds crédités (CREDIT) [walletId={}, amount={}, newAvailable={}, transactionId={}]",
                updated.getId(), amount, newAvailable, transactionId);

        return toResponse(updated);
    }

    @Override
    @Transactional
    public WalletResponse creditFundsByCustomerId(UUID customerId, UUID transactionId, BigDecimal amount) {
        validateAmount(amount);
        WalletEntity wallet = loadByCustomerIdForUpdate(customerId);
        return creditFunds(wallet.getId(), transactionId, amount);
    }

    private WalletEntity loadForUpdate(UUID walletId) {
        if (walletId == null) {
            throw new InvalidOperationException("walletId ne doit pas être null");
        }
        return walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Portefeuille introuvable pour verrouillage pessimiste : " + walletId));
    }

    private WalletEntity loadByCustomerIdForUpdate(UUID customerId) {
        if (customerId == null) {
            throw new InvalidOperationException("customerId ne doit pas être null");
        }
        return walletRepository.findByCustomerIdForUpdate(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Portefeuille introuvable pour verrouillage pessimiste du client : " + customerId));
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidAmountException("Le montant ne peut pas être null");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Le montant doit être strictement positif");
        }
        if (amount.scale() > 2) {
            throw new InvalidAmountException("Le montant ne peut pas comporter plus de 2 décimales (échelle centime)");
        }
    }

    @Override
    @Transactional
    public WalletResponse changeCurrency(UUID customerId, String targetCurrency) {
        if (targetCurrency == null || !CURRENCY_PATTERN.matcher(targetCurrency.trim().toUpperCase()).matches()) {
            throw new InvalidOperationException("Unsupported currency code");
        }
        String target = targetCurrency.trim().toUpperCase();
        if (!USD_RATES.containsKey(target)) {
            throw new InvalidOperationException("Unsupported currency: " + target + ". Supported: EUR, MAD, USD, GBP");
        }

        WalletEntity wallet = loadByCustomerIdForUpdate(customerId);
        String current = wallet.getCurrency() != null ? wallet.getCurrency().toUpperCase() : "USD";
        if (current.equals(target)) {
            return toResponse(wallet);
        }

        BigDecimal fromRate = USD_RATES.getOrDefault(current, BigDecimal.ONE);
        BigDecimal toRate = USD_RATES.get(target);
        BigDecimal newAvailable = wallet.getAvailableBalance()
                .divide(fromRate, 6, RoundingMode.HALF_UP)
                .multiply(toRate)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal newHeld = wallet.getHeldBalance()
                .divide(fromRate, 6, RoundingMode.HALF_UP)
                .multiply(toRate)
                .setScale(2, RoundingMode.HALF_UP);

        wallet.setAvailableBalance(newAvailable);
        wallet.setHeldBalance(newHeld);
        wallet.setCurrency(target);
        wallet.setUpdatedAt(Instant.now());
        WalletEntity saved = walletRepository.save(wallet);
        log.info("Wallet currency converted [customerId={}, from={}, to={}, avail={}, held={}]",
                customerId, current, target, newAvailable, newHeld);
        return toResponse(saved);
    }

    @Override
    @Transactional
    public WalletResponse setCardFrozen(UUID customerId, boolean frozen) {
        WalletEntity wallet = loadByCustomerIdForUpdate(customerId);
        wallet.setCardFrozen(frozen);
        wallet.setUpdatedAt(Instant.now());
        WalletEntity saved = walletRepository.save(wallet);
        log.info("Virtual card freeze state updated [customerId={}, frozen={}]", customerId, frozen);
        return toResponse(saved);
    }

    private WalletResponse toResponse(WalletEntity entity) {
        WalletResponse response = new WalletResponse(
                entity.getId(),
                entity.getCustomerId(),
                entity.getAccountNumber(),
                entity.getCurrency(),
                entity.getAvailableBalance(),
                entity.getHeldBalance(),
                entity.getUpdatedAt()
        );
        response.setCardFrozen(entity.isCardFrozen());
        return response;
    }
}
