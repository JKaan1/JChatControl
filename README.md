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

## Komutlar (`/jchatcontrol`, `/jcc`, `/chatcontrol`)
| Komut | Açıklama |
|-------|----------|
| `/jcc test <mesaj>` | Mesajı test et, hangi aşamada karar verildiğini göster |
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
| `jchatcontrol.notify` | op | Yetkili bildirimleri |
| `jchatcontrol.bypass` | – | Hiç kontrol edilmez |
| `jchatcontrol.bypass.ai` | – | Sadece yerel filtreler |

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
