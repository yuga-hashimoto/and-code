# AndCode

<p align="center">
  <a href="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml"><img src="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/v/release/yuga-hashimoto/and-code" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/yuga-hashimoto/and-code" alt="License: MIT" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/downloads/yuga-hashimoto/and-code/total" alt="Downloads" /></a>
</p>

**شغّل وكلاء البرمجة بالذكاء الاصطناعي محليًا على Android عبر واجهة رسومية أصلية — دون الحاجة إلى طرفية.**

AndCode تطبيق Android أصلي بواجهة رسومية يجلب وكلاء البرمجة بالذكاء الاصطناعي إلى هاتفك. تحدّث مع [OpenCode](https://github.com/sst/opencode) و[Claude Code](https://github.com/anthropics/claude-code) و[Google Antigravity](https://github.com/google-antigravity/antigravity-cli) و[OpenAI Codex](https://github.com/openai/codex) عبر واجهة مصممة للمس — دون طرفية أو SSH أو حاسوب للاستخدام على الجهاز. يشغّل التطبيق بيئات تشغيل الوكلاء عبر PRoot (على الجهاز) أو يتصل عن بُعد بخادم OpenCode الحالي على الحاسوب/Mac/Linux.

[Releases](https://github.com/yuga-hashimoto/and-code/releases/latest)

[English](README.md) | [日本語](README.ja.md) | [简体中文](README.zh-CN.md) | [Español](README.es.md) | [Français](README.fr.md) | [Português (Brasil)](README.pt-BR.md) | [Русский](README.ru.md) | [العربية](README.ar.md)

> [!IMPORTANT]
> AndCode واجهة رسومية مستقلة تعطي الأولوية للعمل المحلي، وتقوم بتثبيت أو تشغيل أدوات سطر أوامر خارجية مدعومة على جهاز Android الخاص بالمستخدم. لا يوفّر AndCode خدمات الذكاء الاصطناعي الأساسية أو الاشتراكات أو الوصول إلى النماذج أو حقوق الحساب، ولا يعيد بيعها. تتولى واجهة CLI الرسمية المعنية أو المزوّد الذي يضبطه المستخدم مسؤولية المصادقة والوصول إلى النماذج والاستدلال والتواصل مع المزوّدين. AndCode غير مرتبط بـ OpenCode أو Anthropic أو Google أو OpenAI، ولا يحظى بتأييدها أو رعايتها أو دعمها الرسمي. راجع [الشؤون القانونية والبرامج الخارجية](#الشؤون-القانونية-والبرامج-الخارجية) أدناه.

<div align="center">

### لقطات الشاشة

<table>
  <tr>
    <td align="center"><img src="screenshots/navigation-drawer.jpg" width="180" alt="درج التنقل مع الوكلاء والمشاريع والمحادثات الأخيرة"><br><em>درج التنقل</em></td>
    <td align="center"><img src="screenshots/model-picker.jpg" width="180" alt="منتقي النموذج وبيئة التشغيل مع إمكانية البحث في النماذج المفضلة"><br><em>منتقي النموذج وبيئة التشغيل</em></td>
    <td align="center"><img src="screenshots/repository-chat.jpg" width="180" alt="محادثة مع وكيل يفحص مستودع AndCode"><br><em>محادثة المستودع</em></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/image-generation.jpg" width="180" alt="صورة أنشأها الوكيل معروضة داخل محادثة"><br><em>توليد الصور</em></td>
    <td align="center"><img src="screenshots/schedules.jpg" width="180" alt="أمر مجدول مع تفعيل التشغيل اليومي"><br><em>الجداول الزمنية</em></td>
    <td align="center"><img src="screenshots/run-result.jpg" width="180" alt="تشغيل مجدول مكتمل مع خيار فتح المحادثة"><br><em>نتيجة التشغيل</em></td>
  </tr>
</table>

</div>

> [!IMPORTANT]
> AndCode مشروع مفتوح المصدر مستقل. **لا** يرتبط بـ OpenCode أو Anthropic أو Google أو OpenAI.

---

## المحتويات

- [الوكلاء المدعومون](#الوكلاء-المدعومون)
- [الميزات](#الميزات)
- [Antigravity](#antigravity)
- [Codex](#codex)
- [OpenCode عن بُعد](#opencode-عن-بُعد)
- [الشاشات](#الشاشات)
- [البدء السريع](#البدء-السريع)
- [الأمان](#الأمان)
- [تفاصيل بيئة التشغيل على الجهاز](#تفاصيل-بيئة-التشغيل-على-الجهاز)
- [التسليم (تبديل بيئة التشغيل أثناء المحادثة)](#التسليم-تبديل-بيئة-التشغيل-أثناء-المحادثة)
- [الاتصال بـ OpenCode Desktop](#الاتصال-بـ-opencode-desktop)
- [البناء من المصدر](#البناء-من-المصدر)
- [التوثيق](#التوثيق)
- [المساهمة](#المساهمة)
- [الشؤون القانونية والبرامج الخارجية](#الشؤون-القانونية-والبرامج-الخارجية)
- [الترخيص](#الترخيص)

## الوكلاء المدعومون

| الوكيل | على الجهاز | حاسوب عن بُعد | الحالة |
|--------|:----------:|:-------------:|--------|
| [OpenCode](https://github.com/sst/opencode) | ✓ | ✓ | مستقر |
| [Claude Code](https://github.com/anthropics/claude-code) | ✓ | — | بيتا |
| [Google Antigravity](https://github.com/google-antigravity/antigravity-cli) | ✓ | — | بيتا |
| [OpenAI Codex](https://github.com/openai/codex) | ✓ | — | بيتا |

تعمل الوكلاء على الجهاز داخل بيئة Linux عبر PRoot. يستخدم OpenCode وClaude Code وCodex نظام Alpine Linux؛ كما يثبّت Google Antigravity نظام Debian Bookworm rootfs إلى جانب Alpine لأن ملف `agy` الثنائي الرسمي مرتبط بـ glibc.

## الميزات

- **واجهة Android أصلية** — واجهة مصممة للمس لوكلاء البرمجة؛ لا حاجة إلى CLI أو طرفية للاستخدام اليومي
- **بيئة تشغيل على الجهاز** — يتم تثبيت Alpine Linux وGit وbash وcurl وripgrep ووكلاء البرمجة تلقائيًا على جهاز Android عبر PRoot
- **المستودع ومساحة العمل** — افتح مستودعات git على الجهاز، وتصفح شجرة الملفات، واعرض الملفات مع تمييز الصياغة
- **ملفات الجهاز** — امنح الوصول إلى كل الملفات ليصبح الهاتف بالكامل (`/sdcard` وبطاقات SD وأقراص USB) قابلًا للتصفح في منتقي المجلدات ومتاحًا للوكيل، ويُفتح في مكانه بدلًا من نسخه إلى التطبيق
- **دعم Git** — تصفح الحالة وراجع فروقات diff من الواجهة؛ واستخدم طرفية بيئة التشغيل المدمجة لعمليات Git الأخرى
- **عارض الفروقات** — راجع تغييرات الشيفرة مباشرة قبل قبولها
- **طرفية مدمجة** — صدفة تفاعلية (PTY حقيقية) داخل بيئة التشغيل على الجهاز: إخراج متدفق، وإدخال قياسي، وCtrl+C، وألوان، وتُفتح في مساحة العمل التي جئت منها. تظهر خوادم التطوير التي تبدأ فيها (Vite، `python -m http.server`، …) كشرائح تفتح الصفحة في المتصفح المدمج. البرامج ملء الشاشة مثل `vim` غير مدعومة بعد
- **شارات pull request** — تبقى طلبات pull request المفتوحة في المحادثة مثبتة فوق حقل الإدخال مع حجم الفرق وحالته (مسودة، مفتوح، تعارض، مدموج، مغلق)؛ اضغط لفتحها على GitHub
- **الموافقة على الأدوات** — وافق على عمليات الأدوات الخطرة أو ارفضها
- **إدارة الجلسات** — أنشئ الجلسات واستأنفها وأعد تسميتها واحذفها
- **المهام المجدولة** — شغّل الأوامر تلقائيًا وفق جدول لمرة واحدة أو متكرر (cron)، مع سجل التشغيل
- **نماذج ديناميكية** — يتم جلب النماذج والمزوّدين والوكلاء مباشرة من نسخة الوكيل المتصلة
- **بث في الوقت الفعلي** — ردود وتنفيذ أدوات وطلبات موافقة مباشرة عبر SSE
- **خط زمني منظم** — استدلالات واستدعاءات أدوات ومخرجات أوامر قابلة للطي
- **الصوت + كلمة التنشيط** — اضغط للتحدث مع التعرف على الكلام في Android واكتشاف كلمة التنشيط
- **تحويل النص إلى كلام** — قراءة الردود بصوت عالٍ
- **المساعد الرقمي** — سجّل التطبيق كمساعد Android الافتراضي (إيماءة الصفحة الرئيسية / السحب من الزاوية)
- **أداة الشاشة الرئيسية** — أطلق أمرًا مباشرة من المشغّل دون فتح التطبيق أولًا
- **تخزين آمن** — بيانات اعتماد الاتصال مشفرة باستخدام Android Keystore
- **واجهة مترجمة** — الإنجليزية واليابانية والصينية (المبسطة) والروسية والإسبانية والفرنسية والبرتغالية (البرازيل) والعربية، ويمكن التبديل من الإعدادات

## Antigravity

يعمل Google Antigravity (`agy`) على الجهاز داخل بيئة PRoot نفسها. وخلافًا لـ OpenCode وClaude Code اللذين يعملان في Alpine Linux، يستخدم Antigravity نظام Debian Bookworm rootfs لتوافق glibc مع ملف CLI الرسمي.

- **تسجيل الدخول عبر OAuth** — المصادقة عبر رابط المتصفح + رمز يُستخدم مرة واحدة؛ تُحفظ بيانات الاعتماد فقط في Linux rootfs (`~/.gemini`) ولا تُحفظ أبدًا في تفضيلات Android
- **اختيار النموذج** — كتالوج نماذج مباشر يُجلب من نسخة `agy` المسجّلة (إصدارات Gemini وClaude وGPT-OSS)
- **أوضاع الأذونات** — تحكم من ثلاث درجات: Plan وAccept Edits وFull Access (`--dangerously-skip-permissions`)
- **خوادم MCP** — قراءة وكتابة `~/.gemini/config/mcp_config.json` مباشرةً، بنفس تنسيق إعداد CLI الرسمي
- **مشغّل الصندوق الرملي** — عملية قائمة على PTY بأبعاد طرفية صريحة؛ يُبقي `AGY_CLI_DISABLE_AUTO_UPDATE=1` التحديثات تحت سيطرة التطبيق

## Codex

تعمل واجهة Codex CLI من OpenAI على الجهاز داخل صندوق Alpine/PRoot الرملي نفسه الخاص بـ OpenCode وClaude Code، وتتواصل مع التطبيق عبر بروتوكول JSON-RPC الخاص بـ `app-server`.

- **تسجيل الدخول** — حساب ChatGPT (عبر المتصفح) أو مفتاح OpenAI API، من خلال نافذة تسجيل الدخول نفسها المستخدمة مع المزوّدين الآخرين
- **خوادم MCP** — تُدار بالأمر الفرعي الرسمي `codex mcp`، لذا لا يعدّل التطبيق `~/.codex/config.toml` يدويًا أبدًا
- **توليد الصور** — تُعرض الصور من أداة `image_gen` المدمجة مباشرة في المحادثة
- **الإعداد** — قابل للتثبيت من دليل الإعداد أو الإعدادات ← الوكلاء ← Codex؛ لا حاجة إلى rootfs منفصل لأن ملف Codex الثنائي المرتبط بـ musl يعمل مباشرة في بيئة Alpine المشتركة

راجع [docs/CODEX.md](docs/CODEX.md) لتفاصيل التنفيذ.

## OpenCode عن بُعد

بالإضافة إلى الوكلاء على الجهاز، يمكن لـ AndCode الاتصال بـ OpenCode الذي يعمل على حاسوبك/Mac/Linux كميزة إضافية:

- **اتصال عن بُعد** — اتصل عبر LAN أو Tailscale
- **تبديل بيئة التشغيل** — انتقل بسلاسة بين التنفيذ المحلي وعن بُعد، حتى أثناء المحادثة (التسليم)
- **الاكتشاف** — اعثر على الحواسيب عبر رمز QR أو mDNS (اكتشاف LAN دون إعداد)

## الشاشات

| الشاشة | الوصف |
|--------|-------|
| المحادثة | الشاشة الرئيسية وعرض المحادثة — الجلسات الأخيرة، الاستدلالات/الأدوات القابلة للطي، الإدخال الصوتي، تبديل النموذج، الموافقات، التسليم |
| مساحات العمل | إعداد بيئة التشغيل المحلية، اتصالات الحاسوب، مجلدات العمل، مستعرض الملفات، عارض الشيفرة، الطرفية المدمجة |
| الجداول الزمنية | إنشاء وتحرير وتمكين/تعطيل ومراجعة سجل تشغيل الأوامر لمرة واحدة أو المتكررة (cron) |
| الإعدادات | الوكلاء والمزوّدون، GitHub، خوادم MCP، ظهور النماذج، الصوت/TTS، المساعد الرقمي، اللغة |

## البدء السريع

### الخيار أ: على الجهاز (دون حاسوب)

1. ثبّت APK من [Releases](https://github.com/yuga-hashimoto/and-code/releases/latest)
2. افتح التطبيق ← اضغط **مساحات العمل** ← **جهاز Android هذا** ← **الإعداد على هذا الجهاز**
3. انتظر تنزيل بيئة التشغيل وتثبيتها (حوالي دقيقتين باتصال جيد)
4. اختر وكيل البرمجة وابدأ المحادثة

### الخيار ب: حاسوب عن بُعد

1. شغّل OpenCode على حاسوبك:

```bash
OPENCODE_SERVER_PASSWORD='your-strong-password' \
  opencode serve --hostname 0.0.0.0 --port 4096 --mdns
```

2. ثبّت APK على جهاز Android
3. افتح التطبيق ← **مساحات العمل** ← **إضافة اتصال**
4. أدخل عنوان IP للحاسوب (أو استخدم **البحث في LAN** / **رمز QR** للاكتشاف التلقائي)

```text
Name:     Mac mini
URL:      http://192.168.1.10:4096
Username: opencode
Password: your-strong-password
```

> يعمل Tailscale أيضًا: `http://100.x.y.z:4096` أو `http://your-mac.tailnet-name.ts.net:4096`

### الإعداد عبر رمز QR

أنشئ رمز QR على حاسوبك:

```bash
npx qrcode "opencode://connect?name=Mac%20mini&url=http%3A%2F%2F192.168.1.10%3A4096&username=opencode&password=your-password&insecure=true"
```

ثم امسحه من **مساحات العمل** ← **الإضافة عبر QR** في التطبيق.

## الأمان

- **لا** تعرض المنفذ 4096 مباشرةً على الإنترنت أبدًا
- استخدم LAN أو Tailscale للاتصال
- استخدم وكيلًا عكسيًا HTTPS على الشبكات العامة
- لا يوافق التطبيق تلقائيًا على العمليات الخطرة أبدًا
- يتطلب HTTP النصي على LAN موافقة صريحة لكل اتصال
- PRoot بيئة تشغيل للتوافق، وليس صندوقًا رمليًا أمنيًا كاملًا. تتيح أوضاع الوصول الكامل / تخطي الأذونات لواجهة CLI الرسمية تنفيذ الأوامر وتعديل الملفات دون سؤال؛ يحذّر التطبيق قبل تفعيلها، لكن يجب عليك مع ذلك نسخ ملفاتك المهمة احتياطيًا وتوجيه الوكيل فقط إلى المستودعات والأوامر وخوادم MCP الموثوقة
- قد تتضمن رموز QR المُنشأة لتدفق `opencode://connect` كلمة مرور اتصال نصية — امسح فقط الرموز من مصدر موثوق، وتعامل مع رمز QR المطبوع أو المعروض كما تتعامل مع مشاركة كلمة المرور تلك

## تفاصيل بيئة التشغيل على الجهاز

عملية الإعداد (تُبدأ من مساحات العمل):

1. تتحقق من مشغّل PRoot الأصلي المضمّن في APK
2. تنزّل Alpine Linux minirootfs من CDN الرسمي
3. تنزّل ملف الوكيل الثنائي من GitHub Releases
4. تتحقق من مجموعتي SHA-256 لكليهما
5. تستخرج إلى دليل خاص بالتطبيق
6. تثبّت Git وbash وcurl وripgrep وشهادات CA داخل Alpine
7. تشغّل خادم الوكيل على `127.0.0.1:4097`
8. تبدّل التطبيق إلى بيئة التشغيل المحلية

الإصدارات المثبّتة (قابلة للتحديث عبر إصدارات التطبيق دون تغيير الوكيل؛ راجع [`local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json)):

- Alpine Linux 3.24.1
- OpenCode 1.18.5
- Google Antigravity CLI 1.1.7 (Debian Bookworm rootfs)
- المعماريات: arm64-v8a، x86_64

## التسليم (تبديل بيئة التشغيل أثناء المحادثة)

من قائمة رأس المحادثة ← **المتابعة على بيئة تشغيل أخرى** — ينشئ التطبيق أمرًا بملخص المحادثة ويرسله إلى بيئة التشغيل المختارة، ما يتيح لك المتابعة من حيث توقفت (مثلًا البدء على الجهاز أثناء التنقل والمتابعة على الحاسوب في المنزل).

## الاتصال بـ OpenCode Desktop

أضف إعداد الخادم إلى `~/.config/opencode/opencode.json`:

```json
{
  "server": {
    "port": 4096,
    "hostname": "0.0.0.0",
    "mdns": true
  }
}
```

أعد تشغيل تطبيق سطح المكتب، ثم اكتشفه من تطبيق Android عبر **البحث في LAN**.

## البناء من المصدر

المتطلبات: JDK 17 وAndroid SDK وPython 3 والوصول إلى الشبكة (أول بناء فقط)

```bash
./gradlew detekt spotlessCheck :app:lintGithubDebug :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubRelease
```

ملفات APK الناتجة:

```text
app/build/outputs/apk/github/debug/app-github-debug.apk
app/build/outputs/apk/github/release/app-github-release-unsigned.apk
```

التثبيت على الجهاز:

```bash
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

## التوثيق

- [تصميم بيئة التشغيل على الجهاز](docs/LOCAL_RUNTIME.md)
- [بيئة Antigravity المحلية](docs/ANTIGRAVITY.md)
- [تصميم تكافؤ وكيل Antigravity](docs/superpowers/specs/2026-07-27-antigravity-agent-parity-design.md)
- [بيئة Codex المحلية](docs/CODEX.md)
- [دليل CI](docs/CI.md)
- [دليل الإصدارات](docs/RELEASE.md)
- [دليل الترجمة](docs/TRANSLATION.md)
- [مصفوفة التحقق من الأجهزة](docs/device-matrix.md)

## المساهمة

نرحّب بجميع المساهمات — الشيفرة وتقارير الأخطاء والترجمات! راجع [CONTRIBUTING.md](CONTRIBUTING.md) للإعداد وسير العمل، و[docs/TRANSLATION.md](docs/TRANSLATION.md) إذا أردت المساعدة في ترجمة التطبيق.

## الشؤون القانونية والبرامج الخارجية

- **تعمل واجهات CLI الرسمية على الجهاز.** OpenCode وClaude Code وGoogle Antigravity وOpenAI Codex هي ملفات ثنائية رسمية غير معدّلة من مشاريعها، نُزّلت من قنوات التوزيع الرسمية وتعمل داخل بيئة Linux PRoot على جهازك (أو، بالنسبة إلى OpenCode، على حاسوبك الخاص PC/Mac/Linux). لا يقوم AndCode بعمل fork أو تعديل أو إعادة تنفيذ لمنطق الوكلاء.
- **أنت تقدّم حسابك أو وصولك إلى API.** لا يبيع AndCode أو يعيد بيع أو يوفر بأي شكل آخر الوصول إلى Claude أو Antigravity أو Codex أو نماذج OpenCode أو GitHub. أنت تصادق بحسابك أو بإعداد المزوّد الخاص بك، وفقًا لشروط الخدمة السارية لدى ذلك المزوّد.
- **لا يمرر AndCode الأوامر أو الرموز عبر خوادمه الخاصة.** لا توجد خدمة خلفية تابعة لـ AndCode تمر عبرها الأوامر أو الملفات أو رموز OAuth. تنتقل الطلبات مباشرة من CLI على الجهاز (أو خادم OpenCode على حاسوبك) إلى المزوّد الذي ضبطته. راجع [docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md).
- **لا تُعاد استخدام رموز OAuth بين الأدوات.** يحتفظ Claude Code وAntigravity وCodex كلٌّ ببيانات اعتماد OAuth الخاصة به داخل Linux/Debian rootfs كما تفعل واجهة CLI الرسمية عادةً؛ ولا ينسخها AndCode أبدًا إلى إعدادات تطبيق Android أو مخزن بيانات اعتماد وكيل آخر أو خدمة خارجية.
- **راجع أيضًا:** [PRIVACY.md](PRIVACY.md) و[TERMS.md](TERMS.md) و[THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md) و[TRADEMARKS.md](TRADEMARKS.md) و[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (تراخيص OSS لمكونات بيئة التشغيل المضمّنة والتبعيات). جميعها متاحة أيضًا دون اتصال من **الإعدادات ← الشؤون القانونية والخصوصية** في التطبيق.

تعيد عملية إنشاء بيئة التشغيل استخدام منطق عام لحل حزم Termux واستخراجها، أُعيد تصميمه لوكلاء البرمجة، مستوحى من تنفيذ Hermes Agent لنظام Android المرخّص بموجب MIT. راجع [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) للحصول على القائمة الكاملة للمكونات الخارجية المضمّنة وتراخيصها.

## الترخيص

**شيفرة AndCode المصدرية** في هذا المستودع مرخّصة بموجب [MIT](LICENSE). يغطي هذا الترخيص شيفرة Kotlin/Android الخاصة بـ AndCode فقط — و**لا** يمتد إلى واجهات CLI أو بيئات التشغيل أو الحزم الخارجية التي يثبّتها أو يشغّلها AndCode (Claude Code وGoogle Antigravity وOpenAI Codex وOpenCode وPRoot وحزم Alpine/Debian وغيرها)، وكل منها يوزّع بموجب ترخيصه الأصلي الخاص. راجع [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) و[TRADEMARKS.md](TRADEMARKS.md).
