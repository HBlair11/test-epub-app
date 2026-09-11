The Livre Magicae — Offline Dictionary

The bundled en.db is derived from WordNet 3.1 (https://wordnet.princeton.edu), a large lexical database of English developed by the Cognitive Science Laboratory at Princeton University. Definitions were compacted for offline lookup (word, part of speech, definition) and are used under WordNet's permissive license — the full license text is preserved in WORDNET_LICENSE.txt in this folder.

The database also includes an irregular-lemma table (mice -> mouse, went -> go, ...) built from WordNet's exception lists so inflected forms resolve to their base entries.

No network access is ever used; lookups run entirely against this local copy. Additional languages can be added later as dict/<lang>.db files using the same schema (words(word, pos, definition) + lemmas(word, base)).
