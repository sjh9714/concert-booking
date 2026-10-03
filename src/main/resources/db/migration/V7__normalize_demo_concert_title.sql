-- Keep existing demo data consistent with the title used by new seeds and artwork.
UPDATE concerts
SET title = 'NOCTURNE: SEOUL'
WHERE title = 'NOCTURNE ' || chr(8212) || ' SEOUL'
  AND artist = 'Studio Lune'
  AND venue = '아르코 아레나';
