-- Default bot is branded "BSC Assistant". V3 seeded it as "Trợ lý tài liệu"; that name is
-- what Teams shows on the greeting card and what the web header reads, so it has to change
-- in the database too, not only in the UI strings.
--
-- Guarded on the old value: the display name is editable from the admin screen, so a
-- deployment that already renamed this bot deliberately must not be overwritten.
UPDATE rag_bots
   SET display_name = 'BSC Assistant',
       updated_at   = now()
 WHERE is_default = true
   AND display_name = 'Trợ lý tài liệu';
