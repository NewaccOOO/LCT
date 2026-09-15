# Gates: бекенд трассировки тепловых сетей

Scope: REST-сервис на Java 11 строит варианты подключения ОКС по правилам CONSTRAINTS.md, а независимый Python-валидатор подтверждает их на синтетических наборах

- [x] G0: пакет спеки связан, гейты не пустышки
  CHECK: python3 ~/.claude/skills/goal-setter/scripts/check_trace.py --input docs/specs/backend-core && node ~/.claude/skills/unlazy/scripts/gate-lint.mjs docs/specs/backend-core/GATES.md
  EXPECT: LINT OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=bcd5c4169670622cbdad6d4590450f1fabcf3b19b0920493b3a396e124f2192d; exit=0; EXPECT=matched; output-sha256=43f9b8273f71e7784f452075c5da268658b1ea1a64e410390527e2e2270a72a9; output-bytes=83; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] G1: скрипты гейтов строгие и не глушат ошибки
  CHECK: bash -c 'set -e; n=0; for f in scripts/gates/*.sh; do [ "$(basename "$f")" = env.sh ] && continue; n=$((n+1)); head -5 "$f" | grep -q "set -euo pipefail" || { echo "no strict mode: $f"; exit 1; }; grep -nE "\|\| *(true|:)|^ *exit 0|; *true *$" "$f" && { echo "error muted: $f"; exit 1; }; done; test "$n" -ge 8 && echo "GATE SCRIPTS STRICT count=$n"'
  EXPECT: /GATE SCRIPTS STRICT count=\d+/
  EVIDENCE: automatic-evidence=v1; definition-sha256=e33c6b8913eb1d7497967f3b57737483b18426d6d0cf30b5852512fe97bf36e3; exit=0; EXPECT=matched; output-sha256=e84f22af0e468e33cc2cec274a39d3462c3b7d2c308a7024b3a0a6a79e9710ad; output-bytes=28; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-0.1: CONSTRAINTS.md не изменён относительно main
  CHECK: bash scripts/gates/constraints_unchanged.sh
  EXPECT: CONSTRAINTS UNCHANGED
  EVIDENCE: automatic-evidence=v1; definition-sha256=531006cb66abe1fc92e496fe3273dc048d42cb068042d81e554a3e131239c472; exit=0; EXPECT=matched; output-sha256=69141ac61dc7605cf5fe5ae28beeba010d869fd18daaf1e6a46438d3ff7071c0; output-bytes=22; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-5.2: сборка и все тесты проходят, ни один тест не пропущен
  CHECK: bash scripts/gates/mvn_verify.sh
  EXPECT: /MVN VERIFY OK tests=([3-9]\d|\d{3,}) skipped=0/
  EVIDENCE: automatic-evidence=v1; definition-sha256=8b548b25cc3807fcc628522053ac4ad1c3fe3be3149968ed76f6ddc5e93c4caa; exit=0; EXPECT=matched; output-sha256=6b0ab86027d1d29240bf6e927a96575caf93bad8af2d97caa79a9eb5c7c6aa06; output-bytes=33; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-5.1: compose поднимает приложение и базу, Swagger отвечает
  CHECK: bash scripts/gates/compose.sh
  EXPECT: COMPOSE OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=4de5d7688bd6831481d55a76541e54f9be199b6cba2b79ad045610bce4b12830; exit=0; EXPECT=matched; output-sha256=f7d5bedcf3aa8b1c1c241c6e1145bf8e1d2af208b6bef6ab3575e021f86c7c60; output-bytes=11; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-4.1: генератор даёт файлы по схеме раздела 12, детерминированно по сиду
  CHECK: bash scripts/gates/synth.sh
  EXPECT: SYNTH SCHEMA OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=43dac7b3eb31cd37b118156e25e2a97187f68b8182b8d0531f25ef1b9b49f2e4; exit=0; EXPECT=matched; output-sha256=c52d9fdcd98290c83233ecc29a11944056ff0b9f38bff4569f278d75c623dc2d; output-bytes=115; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-4.2: средний пресет имеет нужный размер и препятствия на пути
  CHECK: bash scripts/gates/synth.sh
  EXPECT: SYNTH MEDIUM OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=c81b284f939293568dbed8c2a5a34b369a27c8f4847585a139c3140692b1d91e; exit=0; EXPECT=matched; output-sha256=c52d9fdcd98290c83233ecc29a11944056ff0b9f38bff4569f278d75c623dc2d; output-bytes=115; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-4.3: существующая сеть в синтетике связна и монотонна к источнику
  CHECK: bash scripts/gates/synth.sh
  EXPECT: SYNTH NETWORK OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=cb463d2ac295c109d3792bbdf5ce12a3e432553ae34378f2b3bc5e79bb3d029f; exit=0; EXPECT=matched; output-sha256=c52d9fdcd98290c83233ecc29a11944056ff0b9f38bff4569f278d75c623dc2d; output-bytes=115; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-3.3: rules.json совпадает с таблицами CONSTRAINTS.md
  CHECK: uv run --project tools python -m heatcheck.rules_check CONSTRAINTS.md rules/rules.json
  EXPECT: RULES MATCH
  EVIDENCE: automatic-evidence=v1; definition-sha256=d3ed3690101ff6219e58e6f98fc2cf7c4e1d733955d04219b5184c803b73ba3b; exit=0; EXPECT=matched; output-sha256=680d37457c109b55be8bfe99aaeddf02f66211ae154864a054316c5764abaa4e; output-bytes=12; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-3.2: валидатор падает на испорченной фикстуре по каждому из 15 правил
  CHECK: uv run --project tools pytest -q tools/validator/tests
  EXPECT: /^(1[5-9]|[2-9]\d|\d{3,}) passed in [\d.]+s/m
  EVIDENCE: automatic-evidence=v1; definition-sha256=15f140dbcddd9569d167616073cc74be87b7dc9ebc5775eb81d8ece06286bfeb; exit=0; EXPECT=matched; output-sha256=a6f0138d0fc1c491d7f5a7f61bb0ea7c4ed0952ad8258217e9312ea0a82a0f66; output-bytes=99; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] NFR-1: расчёт среднего пресета укладывается в 120 секунд на каждом из трёх сидов
  CHECK: bash scripts/gates/perf.sh
  EXPECT: /PERF medium OK max=\d+s/
  EVIDENCE: automatic-evidence=v1; definition-sha256=eb1c2c8ee44251069ee55d3d2fee75024794db905055b0691aeca3f969c891e7; exit=0; EXPECT=matched; output-sha256=88ecec0a67c2591dd77b12d39e9cf3e1510842f365e50b008626488e93d7878d; output-bytes=105; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-3.1: валидатор принимает выходы сервиса по правилу schema
  CHECK: bash scripts/gates/validate_rule.sh schema
  EXPECT: RULE schema: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=a05505aa3369300c9c4ffcadd8821a10a8888da2350ada94d3f587d03801214a; exit=0; EXPECT=matched; output-sha256=0244389bf53a4e2a4b5b186ed89681b8d6c12b26a45bb6384b028455130cba2f; output-bytes=2142; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.1: каждый ОКС подключён, список неподключённых пуст
  CHECK: bash scripts/gates/validate_rule.sh coverage
  EXPECT: RULE coverage: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=dc46d1965451481a161c148e5d8fa748e0f2692204ca784946bf6e123ce2fc36; exit=0; EXPECT=matched; output-sha256=1ab30b3fa2683afc8c524b21b20ef511389cf0c520974e674d8f89b595b17043; output-bytes=264; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.2: врезка в камеру только по правилу 10 м и четырёх участков
  CHECK: bash scripts/gates/validate_rule.sh tie_in
  EXPECT: RULE tie_in: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=b51dc71fe9c582adade71fbaffaeb996a4ae1a59630676bddf394ef937119a24; exit=0; EXPECT=matched; output-sha256=9806808ceb33d06863a0599b0304e7d60edf2971e80f41d2f76ecaf8b47d5dcd; output-bytes=252; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.3: новая сеть это дерево с ветвлением в камерах без самопересечений
  CHECK: bash scripts/gates/validate_rule.sh topology
  EXPECT: RULE topology: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=befc903d9efebba63e722e9e49f32e3f8ab50ba0f259588700672b1ce12310cc; exit=0; EXPECT=matched; output-sha256=339ba266870a614fdda6b0a845a181393282937b381ade7b478354cf62c7110b; output-bytes=270; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.4: расход участка равен сумме расходов ОКС ниже по дереву
  CHECK: bash scripts/gates/validate_rule.sh flow
  EXPECT: RULE flow: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=acb55bb095112e643792c24c9468562ee6e9639a6fc1cd502dbe94e0f9d45c9c; exit=0; EXPECT=matched; output-sha256=5ed2ca1f1283b849df8bd311a518452a006e177640c3199d70d8f382a2d28deb; output-bytes=246; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.5: диаметр участка минимальный по таблице пропускной способности
  CHECK: bash scripts/gates/validate_rule.sh diameter
  EXPECT: RULE diameter: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=d5045965a55db638c6062d3d329efa942dfec71b93b5fa2356434fd26c97bca0; exit=0; EXPECT=matched; output-sha256=82ec276b8c9ba6a73d93a569ed3a0b6323f6c96b3cd04e12c3821aa694a2e98f; output-bytes=270; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.6: цепочки одного диаметра не длиннее предельной длины
  CHECK: bash scripts/gates/validate_rule.sh length_limit
  EXPECT: RULE length_limit: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=e9ca1593eb4377da9fc610b3729bb944d81031028cb986fd0f6ee56b28a7fc1e; exit=0; EXPECT=matched; output-sha256=28bed8749062d21fdc502edbce69d37ea3443c57542f9c627698046c3c6c01ef; output-bytes=294; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.7: запрещённые объекты обойдены с отступом, специальные проходы оформлены и под углом
  CHECK: bash scripts/gates/validate_rule.sh forbid && bash scripts/gates/validate_rule.sh special
  EXPECT: /RULE forbid: PASSED[\s\S]*RULE special: PASSED/
  EVIDENCE: automatic-evidence=v1; definition-sha256=b7fb5821ea53fa7b41b85db13d6cef43d13aced1dc5a5f4f0bf8790bb56182d1; exit=0; EXPECT=matched; output-sha256=b356a77af3171500bbe8bcbd0a838dec2cb2e2aa280fb86a6c9a3574297e4fc4; output-bytes=522; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.8: реконструкция существующих участков по цепочке к источнику
  CHECK: bash scripts/gates/validate_rule.sh reconstruction
  EXPECT: RULE reconstruction: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=43423f9ae3bc6b15afa93561fc72ba0aeb2c648e55a664ea3966e40caf18d1ab; exit=0; EXPECT=matched; output-sha256=e90d3992a481a3de52dec129dec9a8d8f42b2f3dbd15d0794756552f66e31830; output-bytes=306; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.9: реконструкция камер врезки только при превышении диаметра
  CHECK: bash scripts/gates/validate_rule.sh chamber_recon
  EXPECT: RULE chamber_recon: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=32e571c1e05db26afce02f1df00f7aa9f671fb6e3ec974ac2fb099a2a76eb9b5; exit=0; EXPECT=matched; output-sha256=ada133895a2e80d9d819a9828c28b2487925a7af80a26849c0758260692a8bde; output-bytes=294; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.10: стоимости пересчитываются по формулам с расхождением до 1 рубля
  CHECK: bash scripts/gates/validate_rule.sh cost
  EXPECT: RULE cost: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=cd5f43765ac6e0d19e034abf59449f59aab6dd2148ca038dadfc5c3032c09013; exit=0; EXPECT=matched; output-sha256=d5bf1294a1f11f99dc511d7e64ab69e1ced2aa88c616411f96c4c0ddfd40e76e; output-bytes=246; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.11: score по формуле, ранги по возрастанию
  CHECK: bash scripts/gates/validate_rule.sh score
  EXPECT: RULE score: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=32f92ff08e1ac58aee9f03e356c447c70bbd474a9fb1bb6852bbbdc5afefd963; exit=0; EXPECT=matched; output-sha256=619384288a6f4e8a19e2718cfb5bcf6b7572932cdfd17fb728c1269820e26fbb; output-bytes=243; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.12: не меньше двух содержательно разных вариантов
  CHECK: bash scripts/gates/validate_rule.sh variants
  EXPECT: RULE variants: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=a130b9b98f934daa6d97e852ec87a61dca2ccf5009c9c673710f74e569334677; exit=0; EXPECT=matched; output-sha256=aaa71f1e15c13775e39e7e5e39baebc72ca88f08dc2cc203c42a207938766e09; output-bytes=261; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-2.13: трассы прямые, без лишних вершин и ступенек
  CHECK: bash scripts/gates/validate_rule.sh geometry
  EXPECT: RULE geometry: PASSED
  EVIDENCE: automatic-evidence=v1; definition-sha256=19279983935468523c3bb0958708a34728c4e9603198d61520f29181f30e0e44; exit=0; EXPECT=matched; output-sha256=638e12e45349a13300b7eeb9a970dea9fdcb4ef918752871d314da970e133928; output-bytes=270; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-1.1: загрузка файла отвечает 202 с id, пустое тело отвечает 400
  CHECK: bash scripts/gates/api.sh AC-1.1
  EXPECT: API AC-1.1 OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=8075dda5940f3ba178601bd7872363aad4f2ed75943af727cf6f7b047faa5bd2; exit=0; EXPECT=matched; output-sha256=fb160cd98e1977d5dec8b624d3f2f37fca18ef84e2a8e0ef3284b24df251cf84; output-bytes=481; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-1.2: статус задачи проходит QUEUED, RUNNING, DONE и содержит сводки
  CHECK: bash scripts/gates/api.sh AC-1.2
  EXPECT: API AC-1.2 OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=9db6dd0e7a4f1fdac0ee52b888aa23260f6921597ba6be7d93f645b8a9df4c93; exit=0; EXPECT=matched; output-sha256=292ccb641a6e9de3c887ccd8078b406a13dd9b169cd33ce0a95ac2dbe30481c5; output-bytes=252; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-1.3: результат и вход скачиваются, до DONE результат отвечает 409
  CHECK: bash scripts/gates/api.sh AC-1.3
  EXPECT: API AC-1.3 OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=60819aa315212b8ba5a4e44e4a91696b7ae8103decffc6fd75082deda2702850; exit=0; EXPECT=matched; output-sha256=67788193ab54acf8168db79bd195176e1fac6dd82fad1bd6fddec1ffaae3ab40; output-bytes=284; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] AC-1.4: битый вход даёт FAILED со списком диагностик
  CHECK: bash scripts/gates/api.sh AC-1.4
  EXPECT: API AC-1.4 OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=a9ab1c8a89ce6ab8c7b7351c2f97ff8828508efaee297f8a7a7790e3723ad697; exit=0; EXPECT=matched; output-sha256=6e8127fcae7fac78197d3d3c9715ac04cb8e89a13a488a32ed598ec2358b3c65; output-bytes=252; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] NFR-2: файл 300 МБ проходит через API при куче 1 ГБ
  CHECK: bash scripts/gates/api.sh NFR-2
  EXPECT: API NFR-2 OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=9f106c398f541a68bdbdff782e18064cd9fe05859a6a950f193c218f56f05834; exit=0; EXPECT=matched; output-sha256=48ea6cc935371fa08af9a5e8b587e691b80ada2f4c1780fc71f690cad0d2e10d; output-bytes=251; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries

- [x] NFR-3: пять одновременных задач завершаются за 180 секунд
  CHECK: bash scripts/gates/api.sh NFR-3
  EXPECT: API NFR-3 OK
  EVIDENCE: automatic-evidence=v1; definition-sha256=0102831fab9ad229ee8a4a4dae8c6ef2acc4611e888fffdc66ad1becf2d5df22; exit=0; EXPECT=matched; output-sha256=f770e214d1acacfced427e67c30227f1e3c80995866b92b322bf01edc0e2a9fb; output-bytes=251; shell=/bin/sh; cwd=/Users/paveldurynin/L/LCT; path=253c039e0374/25 entries
