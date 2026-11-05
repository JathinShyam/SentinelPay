-- Law 5: transactions are append-only too. Even an unreferenced envelope must
-- never be deleted or rewritten; corrections are new REVERSAL envelopes, and the
-- effective status is derived from the reversal_of link (D-1).
CREATE OR REPLACE FUNCTION prevent_transaction_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'transactions table is append-only. Use a compensating REVERSAL transaction instead.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_immutable_transactions
BEFORE UPDATE OR DELETE ON transactions
FOR EACH ROW EXECUTE FUNCTION prevent_transaction_mutation();
