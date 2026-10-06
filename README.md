# JChatControl

**Yapay zeka destekli sohbet denetimi — Minecraft 1.21+ (Spigot / Paper / Purpur)**
Author: **JKaanOF**

JChatControl sohbeti **küfür, hakaret, ırkçılık, din, dil, siyaset, cinsellik, tehdit ve reklam** içeriğinden temizler.
Yerel yapay zeka sunucularında (Ollama, LM Studio, vLLM, llama.cpp ...) veya API anahtarıyla kullanılan modellerde
(OpenAI, Anthropic Claude, Google Gemini, Groq, OpenRouter ...) çalışır. **Karar modellerini** de destekler
(Llama Guard, OpenAI Moderation, Google Perspective, kendi sınıflandırıcın).

Asıl hedef: **API kullanımını en aza indirmek.** Mesajların büyük çoğunluğu yapay zekaya hiç gitmez.

---

## Nasıl çalışır? (en ucuzdan en pahalıya)

| # | Aşama | API çağrısı |
|---|-------|-------------|
| 0 | **Anti-spam / chat delay** – mesaj arası bekleme, aynı/benzer mesaj tekrarı, kısa sürede çok mesaj (flood). Spam hiçbir filtreye ve yapay zekaya ulaşmaz | yok |
| 1 | **Normalizasyon** – büyük/küçük harf, `ı ş ğ ü ö ç`, leetspeak (`4→a 3→e $→s`), harf tekrarı (`siiiik`), Kiril benzeri harfler | yok |
| 2 | **Yasaklı kelimeler** – `exact` / `prefix` (Türkçe ekler) / `contains` (Aho-Corasick, tek geçiş) + aralıklı yazım (`s i k t i r`) | yok |
| 3 | **Regex desenleri** – reklam, IP, link, kalıplar (`patterns.yml`) | yok |
| 4 | **Karar önbelleği** – aynı (normalize) mesaj daha önce sorulduysa (`SA!!`, `saaa`, `sa` aynı kayıt) | yok |
| 5 | **İzinli kelimeler** – mesajdaki tüm kelimeler biliniyorsa (2000+ kelimelik liste + Türkçe ek desteği + oyuncu isimleri) | yok |
| 6 | **Yapay zeka** – batch (çok mesaj tek istek), tekilleştirme, rate-limit, sağlayıcı zinciri / eskalasyon | **var** |

**Öğrenme:** Yapay zekanın her kararı dosyalara yazılır:
- Temiz bulunan bilinmeyen kelimeler, N farklı mesajda görülünce `learned/allowed-words.txt`'ye eklenir.
- Yapay zekanın bildirdiği küfür/hakaret kelimeleri `learned/blocked-words.txt`'ye eklenir (isteğe bağlı yetkili onayı).
- Mesaj kararları `data/ai-cache.tsv`'de saklanır.

Böylece sunucu çalıştıkça API kullanımı **sürekli azalır**. `/jcc stats` ile "API'siz karar verilen mesaj yüzdesi"ni görebilirsin.

### Diğer API tasarrufu ayarları
- `ai.trigger`: `UNKNOWN_WORDS` (önerilen) · `SUSPICIOUS_ONLY` (sadece din/siyaset/ırk gibi şüpheli kelimelerde) · `ALWAYS`
- `ai.batch`: 150 ms içinde gelen mesajlar tek istekte, sistem promptu bir kez ödenir
- `ai.limits`: dakikalık global istek limiti + oyuncu başına limit (spam ile API yakılamaz)
- `ai.strategy: ESCALATE`: ücretsiz/ucuz karar modeli önce bakar, sadece **emin olmadığı** mesajlar büyük modele gider
- Anthropic sağlayıcısında sistem promptu **prompt caching** ile önbelleğe alınır

---

## Kurulum

1. `JChatControl-x.y.z.jar` dosyasını `plugins/` klasörüne at, sunucuyu başlat.
2. `plugins/JChatControl/config.yml` içinde `ai.chain` ve `ai.providers` bölümünü düzenle.
3. `/jcc reload`

### Yerel yapay zeka (ücretsiz) – Ollama
```bash
ollama pull qwen2.5:3b          # veya llama3.2:3b, gemma2:2b, qwen2.5:7b ...
```
```yaml
ai:
  chain: [ollama]
```

### LM Studio / vLLM / llama.cpp / LocalAI / Jan
`type: openai` + `base-url: http://localhost:1234/v1` (OpenAI uyumlu her sunucu çalışır).

### API anahtarı ile
```yaml
ai:
  chain: [groq, openai]        # Groq çalışmazsa OpenAI
  providers:
    groq:
      api-key: "env:GROQ_API_KEY"   # anahtarı ortam değişkeninden oku (veya doğrudan yaz)
```
Hazır örnekler: `openai`, `groq`, `openrouter`, `anthropic`, `gemini`.

### Karar modelleri
| Sağlayıcı | Tür | Not |
|-----------|-----|-----|
| `llama-guard` | yerel (Ollama) | `ollama pull llama-guard3:1b`, `parser: guard`, kategori kodları `category-map` ile eşlenir |
| `openai-moderation` | API | OpenAI anahtarıyla **ücretsiz**, batch destekler |
| `perspective` | API | Google Perspective toxicity skorları |
| `custom` | HTTP | Kendi sınıflandırıcın (Detoxify, BERT, HuggingFace ...) – istek gövdesi ve cevap yolları ayarlanabilir |

`parser` seçenekleri: `json` (LLM'ler, batch), `guard` (`safe`/`unsafe S10`), `yes-no`, `label`.

**En düşük maliyetli kurulum örneği:**
```yaml
ai:
  chain: [openai-moderation, ollama]
  strategy: ESCALATE
```

---

## Sohbet modları (`chat.mode`)
- **BLOCKING** – mesaj AI cevabına kadar (max `blocking-timeout-ms`) async thread'de bekletilir. TPS etkilenmez.
- **DELAYED** – mesaj tutulur, AI onaylayınca plugin gönderir.
- **POST** – mesaj hemen gider, AI arkadan kontrol edip ceza verir.

Yerel filtreler her modda anında çalışır. Özel mesajlar (`/msg`, `/r` ...), tabelalar ve kitaplar da kontrol edilir.

## Kategoriler ve işlemler
Her kategori için `action` (`BLOCK`, `CENSOR`, `WARN`, `LOG`), ihlal puanı ve yapay zeka açıklaması ayarlanır.
Yeni kategori ekleyebilirsin. Puan eşiklerinde istediğin komut çalışır (dahili `jcc mute` veya başka ceza pluginleri).

## Anti-spam ve chat delay
```yaml
anti-spam:
  chat-delay-ms: 1500            # iki mesaj arası en az bekleme
  duplicate: {window-seconds: 30, history: 3, similarity: 0.85}
  burst: {messages: 5, window-seconds: 8}
  apply-to-commands: true        # /msg, /r ... (ayrı sayaç)
  punish: {threshold: 5, window-seconds: 60, commands: ['jcc mute {player} 2m Spam']}
```
Benzerlik normalize edilmiş metin üzerinden ölçülür: `selam!!`, `SELAAAM` ve `selam` aynı mesaj sayılır.
Yetkiler: `jchatcontrol.bypass.spam` (anti-spam kapalı), `jchatcontrol.bypass.delay` (sadece bekleme süresi kapalı).

## İhlal komutları (kelime listesi / regex / AI için ayrı)
Her ihlalde, ihlali **hangi aşamanın** yakaladığına göre farklı komut çalıştırılabilir (susturma, uyarı, başka bir ceza plugini ...):
```yaml
violation-commands:
  enabled: true
  default:                # kategoride o aşama yazılmamışsa kullanılır
    word-list: []
    regex: []
    ai: []
  categories:
    profanity:
      word-list: ['jcc mute {player} 2m Küfür (kelime filtresi)']
      regex:     ['jcc mute {player} 2m Küfür (kalıp)']
      ai:        ['jcc mute {player} 5m Küfür (yapay zeka)']
```
`cache` (yapay zekanın daha önce karar verdiği aynı mesaj) ayrıca yazılmazsa `ai` komutları kullanılır.
Yer tutucular: `{player} {uuid} {category} {category_id} {source} {words} {message} {points} {context}`.
Bu komutlar puan eşiği komutlarından **önce** çalışır. Dahili susturma, oyuncunun mevcut daha uzun susturmasını kısaltmaz.

## Test
- `/chattest <mesaj>` (kısayol: `/ctest`, yetki `jchatcontrol.test`) veya `/jcc test <mesaj>` – mesajın hangi aşamada
  yakalandığını, kategoriyi, sansürlü halini ve **gerçek ihlalde çalışacak komutları** gösterir (komutları çalıştırmaz).
- `-l` ekle (`/chattest -l <mesaj>`) – sadece yerel filtreler, yapay zekaya sorulmaz.
- `/jcc simulate <oyuncu> <mesaj>` – oyuncu yazmış gibi her şeyi uygular (engelleme, bildirim, puan, **komutlar gerçekten çalışır**).

## Komutlar (`/jchatcontrol`, `/jcc`, `/chatcontrol`)
| Komut | Açıklama |
|-------|----------|
| `/jcc test [-l] <mesaj>` | Mesajı test et: aşama, kategori, çalışacak komutlar |
| `/chattest [-l] <mesaj>` | Kısa test komutu (`jchatcontrol.test`) |
| `/jcc simulate <oyuncu> <mesaj>` | Oyuncu yazmış gibi uygula (komutlar çalışır) |
| `/jcc stats` | İstatistikler, API tasarrufu, sağlayıcı durumu |
| `/jcc allow / unallow <kelime>` | İzinli kelime ekle / çıkar |
| `/jcc block <kelime> [kategori]` / `unblock <kelime>` | Yasaklı kelime ekle / çıkar |
| `/jcc learn [list\|approve\|deny\|approveall]` | AI'nın önerdiği kelimeler (onay modu) |
| `/jcc ai [status\|on\|off]` | Yapay zeka kontrolü |
| `/jcc cache [clear]` | Karar önbelleği |
| `/jcc mute <oyuncu> <süre> [sebep]` / `unmute <oyuncu>` | Dahili susturma (`30s`, `10m`, `2h`, `1d`, `perm`) |
| `/jcc violations <oyuncu> [reset]` | İhlal puanları |
| `/jcc reload` / `/jcc save` | Yeniden yükle / kaydet |

## Yetkiler
| Yetki | Varsayılan | Açıklama |
|-------|-----------|----------|
| `jchatcontrol.admin` | op | Komutlar |
| `jchatcontrol.test` | op | `/chattest` |
| `jchatcontrol.notify` | op | Yetkili bildirimleri |
| `jchatcontrol.bypass` | – | Hiç kontrol edilmez |
| `jchatcontrol.bypass.ai` | – | Sadece yerel filtreler |
| `jchatcontrol.bypass.spam` | – | Anti-spam uygulanmaz |
| `jchatcontrol.bypass.delay` | – | Chat delay uygulanmaz |

## Dosyalar
```
plugins/JChatControl/
├── config.yml
├── lang/tr.yml, en.yml
├── filters/
│   ├── allowed-words.txt      # izinli kelimeler (uzun liste)
│   ├── blocked-words.yml      # yasaklı kelimeler (exact / prefix / contains)
│   ├── suspicious-words.txt   # her zaman AI'ya sorulan hassas kelimeler (din, siyaset, ırk, belirsiz argo)
│   └── patterns.yml           # regex
├── learned/                   # yapay zekadan öğrenilenler (elle de düzenlenebilir)
│   ├── allowed-words.txt
│   ├── blocked-words.txt
│   ├── pending-words.txt
│   └── candidates.tsv
├── data/ai-cache.tsv, mutes.yml
└── logs/violations-YYYY-MM-DD.log
```

## Gerçek bir modelle test (sunucu gerekmeden)
Tüm pipeline'ı (yerel filtreler + önbellek + öğrenme + gerçek yapay zeka) örnek Türkçe/İngilizce mesajlarla çalıştırır,
doğruluk ve API istek sayısını raporlar (`target/live-ai-report.txt`):
```bash
JCC_LIVE_API_KEY=sk-or-...  JCC_LIVE_MODEL=meta-llama/llama-3.1-8b-instruct  mvn test -Dtest=LiveAiTest
# opsiyonel: JCC_LIVE_BASE_URL (varsayılan https://openrouter.ai/api/v1), JCC_LIVE_PARSER (json|guard|yes-no|label),
#            JCC_LIVE_JSON_FORMAT=false (response_format desteklemeyen modeller), JCC_LIVE_DEBUG=true
```

## Derleme
```bash
mvn package      # Java 21
```
Çıktı: `target/JChatControl-1.0.0.jar`

---

### English (short)
JChatControl is an AI-assisted chat filter for Minecraft 1.21+. Messages pass a chain of cheap local stages
(normalization, blocked word lists with Aho-Corasick, regex, decision cache, a 2000+ word allow-list with Turkish suffix
support) and only messages with unknown or sensitive words reach the AI. AI requests are batched, de-duplicated,
rate-limited and can fall back or escalate across providers (Ollama, any OpenAI-compatible server, OpenAI, Anthropic,
Gemini, Groq, OpenRouter, Llama Guard, OpenAI Moderation, Perspective, custom HTTP classifiers). Every AI decision is
cached and learned into the word lists, so API usage keeps dropping over time. Set `language: en` in `config.yml`.

License: MIT
