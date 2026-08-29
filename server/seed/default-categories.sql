-- A household starts with default categories (PRD §15).
--
-- Starting empty would mean the first expense costs a detour through category
-- setup, which is exactly the friction §9 exists to prevent. The family renames
-- and extends them from Settings.
--
-- Names are Polish. Seed language is a one-time choice made per household and
-- is NOT what the in-app language picker changes: these are rows that sync to
-- every device, and the family renames them anyway. scripts/new-household.mjs
-- --lang en seeds the English set instead. Run once per household:
-- every :hh placeholder is the household id, and each row draws its own seq the
-- same way a push does.
--
-- Prefer scripts/new-household.mjs, which fills the placeholders, allocates the
-- seqs and mints the first invite in one go.

-- Reserve a range for the 8 categories + 2 accounts below.
UPDATE households SET next_seq = next_seq + 10 WHERE id = :hh;

INSERT INTO accounts (id, household_id, name, icon, color, initial_balance_minor, sort_order, seq, deleted) VALUES
  (:acc_cash, :hh, 'Gotówka', 'wallet', 'green', 0, 0, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 1, 0),
  (:acc_card, :hh, 'Karta',   'wallet', 'sky',   0, 1, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 2, 0);

INSERT INTO categories (id, household_id, parent_id, name, icon, color, kind, sort_order, seq, deleted) VALUES
  (:cat_groceries, :hh, NULL, 'Zakupy spożywcze', 'groceries', 'green',  'expense', 0, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 3, 0),
  (:cat_transport, :hh, NULL, 'Transport',        'transport', 'sky',    'expense', 1, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 4, 0),
  (:cat_home,      :hh, NULL, 'Dom',              'home',      'brown',  'expense', 2, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 5, 0),
  (:cat_health,    :hh, NULL, 'Zdrowie',          'health',    'coral',  'expense', 3, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 6, 0),
  (:cat_fun,       :hh, NULL, 'Rozrywka',         'fun',       'violet', 'expense', 4, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 7, 0),
  (:cat_kids,      :hh, NULL, 'Dzieci',           'kids',      'amber',  'expense', 5, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 8, 0),
  (:cat_salary,    :hh, NULL, 'Wypłata',          'salary',    'teal',   'income',  6, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 9, 0),
  (:cat_other_inc, :hh, NULL, 'Inne przychody',   'gift',      'olive',  'income',  7, (SELECT next_seq FROM households WHERE id = :hh) - 10 + 10, 0);
