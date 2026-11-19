package dev.ledger;

import dev.ledger.AccountService.Account;
import dev.ledger.AccountService.Entry;
import dev.ledger.TransferService.TransferResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class LedgerController {

    public record CreateAccountRequest(@NotBlank String name) {}

    public record TransferRequest(@NotNull UUID fromAccountId, @NotNull UUID toAccountId, @Positive long amountCents) {}

    private final AccountService accounts;
    private final TransferService transfers;

    public LedgerController(AccountService accounts, TransferService transfers) {
        this.accounts = accounts;
        this.transfers = transfers;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public Account createAccount(@Valid @RequestBody CreateAccountRequest req) {
        return accounts.create(req.name());
    }

    @GetMapping("/accounts/{id}/balance")
    public Map<String, Object> balance(@PathVariable UUID id) {
        return Map.of("accountId", id, "balanceCents", accounts.balance(id));
    }

    @GetMapping("/accounts/{id}/entries")
    public List<Entry> entries(@PathVariable UUID id,
                               @RequestParam(defaultValue = "50") int limit,
                               @RequestParam(required = false) Long before) {
        return accounts.entries(id, Math.min(Math.max(limit, 1), 200), before);
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResult> transfer(@RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody TransferRequest req) {
        if (key.isBlank() || key.length() > 200) {
            throw new LedgerException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1-200 characters");
        }
        TransferResult result = transfers.transfer(key, req.fromAccountId(), req.toAccountId(), req.amountCents());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }
}
