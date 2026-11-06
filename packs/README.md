# Remote packs

Add `https://raw.githubusercontent.com/<owner>/<repo>/main/packs/index.json` in **CONFIG → remote pack URLs**
(private repos are not reachable without a token — host the files somewhere public).

Format (authoring):

```json
{"topic":"OCCULT","prompts":[{"id":"occ.cryptid","q":"Name a cryptid","a":"Bigfoot=Sasquatch|Mothman|~Jackalope|^Tsuchinoko"}]}
```

- answers separated by `|`, most familiar first (position decides the tier)
- aliases after `=`
- `~` = COPYPASTA (the "clever" pick everyone uses), `^` = ZERO_DAY (the single gem)

`index.json` lists pack files: `{"packs":["geography.json","code.json"]}`.
