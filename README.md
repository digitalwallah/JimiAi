# Jimi — Personal Android AI Assistant

Ye ek complete Android Studio project hai jo tumhare phone pe **sideload** hoga (Play Store pe nahi jaayega — reason neeche explain hai). Jimi:

- Free-form Hindi/English/Hinglish command samajhta hai (Claude API se)
- WhatsApp pe kisi bhi contact ko unke usual style me message bhej sakta hai
- YouTube pe kisi bhi channel ka video dhoondh kar play kar sakta hai
- Phone me installed kisi bhi app ko naam bol kar open kar sakta hai

---

## ⚠️ Pehle ye 3 cheezein samajh lo

1. **WhatsApp ka koi official personal-account API nahi hai.** Jimi `wa.me` link (WhatsApp ka apna official deep-link) se chat kholta hai aur message pre-fill karta hai, phir Accessibility Service se Send button tap karta hai — bilkul waise jaise tumhari ungli tap karti. Ye kaam karta hai, lekin ye WhatsApp ki UI automate karna hai, unki official API nahi. Zyada volume me messages bhejoge toh number flag/ban hone ka risk hai. Apne personal use ke liye theek hai, business-scale spam ke liye nahi.

2. **Accessibility Service permission bahut powerful hai** — jo app iska use kare wo screen pe sab kuch dekh/tap kar sakti hai. Isliye Play Store aise apps ko strict review karta hai aur aksar reject karta hai jab tak wo genuinely accessibility-disabled users ke liye na ho. Isliye ye app tum sirf apne phone pe **sideload** karoge, Play Store pe publish nahi karoge.

3. **Default mode "review before send" hai** — Jimi WhatsApp message draft karke chat khol dega, lekin khud Send nahi dabayega jab tak tum Settings me "Auto-send" on na karo. Ye safety ke liye hai (galat contact ko galat message na chala jaaye).

---

## Step 1 — API Keys lo (dono free hain)

1. **Claude API key**: [console.anthropic.com](https://console.anthropic.com) pe account banao → API Keys → naya key generate karo.
2. **YouTube Data API key**: [Google Cloud Console](https://console.cloud.google.com) → naya project → "YouTube Data API v3" enable karo → Credentials → API Key generate karo.

## Step 2 — Project build karo

### Option A: Laptop/PC hai

1. [Android Studio](https://developer.android.com/studio) install karo (agar nahi hai).
2. Ye poora `JimiAI` folder Android Studio me **Open** karo (File → Open).
3. Gradle sync hone do (pehli baar 2-5 min lag sakta hai, internet chahiye).
4. Phone ko USB se connect karo:
   - Phone pe **Settings → About Phone → Build Number** pe 7 baar tap karo (Developer Options unlock hoga)
   - **Settings → Developer Options → USB Debugging** ON karo
   - Phone connect karke "Allow USB Debugging" popup accept karo
5. Android Studio me top bar se apna phone select karo, phir green **Run ▶** button dabao.
6. App phone pe install ho jayega.

*(Agar Android Studio nahi use karna, `./gradlew assembleDebug` se command line se bhi APK ban sakta hai, phir `adb install app/build/outputs/apk/debug/app-debug.apk` se install karo.)*

### Option B: Sirf mobile hai (koi computer nahi)

Is project me **GitHub Actions** workflow already daala hua hai (`.github/workflows/build.yml`) — jo GitHub ke servers pe automatically APK build karega. Tumhe sirf phone se yeh karna hai:

1. **Termux app install karo** (F-Droid se — Play Store wala version purana/broken hai): [f-droid.org/packages/com.termux](https://f-droid.org/packages/com.termux)
2. **GitHub account banao** (agar nahi hai) — [github.com](https://github.com) phone browser se, phir ek naya **empty repository** banao (jaise naam "JimiAI", Public).
3. **GitHub Personal Access Token banao** (password ki jagah yeh use hota hai push karne ke liye): GitHub → Settings → Developer settings → Personal access tokens → Generate new token (classic) → scope me "repo" check karo → Generate → token copy karke kahin save kar lo (dobara nahi dikhega).
4. Ye `JimiAI.zip` (jo maine chat me diya) apne phone me **Downloads** folder me hona chahiye.
5. **Termux kholo** aur ye commands ek-ek karke chalao:
   ```
   pkg update -y && pkg upgrade -y
   pkg install -y git unzip
   termux-setup-storage
   ```
   (ek permission popup aayega, "Allow" karo)
   ```
   cd storage/downloads
   unzip JimiAI.zip
   cd JimiAI
   git init
   git add .
   git commit -m "Jimi AI"
   git branch -M main
   git remote add origin https://github.com/TUMHARA-USERNAME/JimiAI.git
   git push -u origin main
   ```
   (username maangega → apna GitHub username; password maangega → wahan **Personal Access Token paste karo**, password nahi)
6. Push hone ke baad browser me apne repo pe jao → **Actions** tab → build chalte dikhega (~4-5 min lagenge, ek green tick aayega jab done ho).
7. Build complete hone pe repo ke right side **"Releases"** section me jao → wahan `app-debug.apk` file milegi → tap karke **download karo**.
8. Downloaded APK pe tap karo install karne ke liye. Android ek warning dega "Install blocked" ya "Unknown source" — ye normal hai kyunki ye Play Store se nahi hai. **Settings → uss app ko allow karo "Install unknown apps"** (jis app se open kiya, jaise Files ya Chrome), phir dobara APK pe tap karke Install karo.

Bas — Jimi phone pe install ho jayega, koi computer nahi lagega.

## Step 3 — Phone pe Jimi setup karo

1. App kholo → **"API Keys Set Karo"** dabao → dono keys paste karo → Save.
2. **"Accessibility Permission On Karo"** dabao → Settings khulegi → "Jimi" dhoondho → ON karo → confirm karo.
3. Ab wapas Jimi app me aao, "Accessibility service: ON ✅" dikhna chahiye.

## Step 4 — Use karo

Command box me **type karo** ya 🎤 **mic button dabake bolo** — dono kaam karte hain, jaise:
- `Rahul ko WhatsApp pe bolo main 10 min me pahuch raha hu`
- `MrBeast ka latest video YouTube pe chalao`
- `Instagram khol do`

Mic button pehli baar dabaoge toh Android record-audio permission maangega, allow kar dena. Jimi apna reply text me dikhayega **aur** bolke bhi sunayega (text-to-speech).

## 🎙️ Always Listening ("Jimi" bolke jagao, screen off ho tab bhi)

Switch **3. Always Listening** ON karo — ab phone pocket me ho, screen off ho, phir bhi bas "**Jimi**" bolo aur uske baad apna command bolo (jaise: *"Jimi... Rahul ko WhatsApp pe bolo main pahuch raha hu"*).

**Kaise kaam karta hai:**
1. Ek background service hamesha chhote-chhote bursts me sunta rehta hai
2. "Jimi" sunte hi screen wake ho jaati hai aur agla jo bologe wo command ban jaata hai
3. Command wahi `CommandRouter` se process hota hai jo text/mic button use karta hai
4. Reply bolke bhi sunaya jaata hai

**3 zaroori limits jo samajhna zaroori hai:**
- **Phone poori tarah power-off ho toh kuch nahi chalega** — koi bhi app tab kaam nahi karti.
- **Screen-lock (PIN/pattern/fingerprint) ko Jimi bypass NAHI karega** — security ke liye. "Jimi" bolne pe screen wake ho jayegi, agar locked hai toh ek baar khud unlock karna padega, uske baad command chalega.
- **Battery zyada use hogi** — continuous listening ke liye Android ka built-in speech recognizer use ho raha hai (Google Assistant jaisa dedicated low-power "wake word chip" nahi). Setup karte waqt Android ek popup dega "battery optimization se exempt karo" — allow kar dena, warna kuch der baad Android service ko khud band kar dega battery bachane ke liye. Agar battery drain zyada lage, isko off rakh ke sirf mic-button wala mode use karo.

**Contact ka style set karna** (taaki message unke andaaz me jaaye): abhi ye Room database me manually store hota hai — agar chaho toh main ek Settings screen bhi bana sakta hoon jahan har contact ke liye "isse Hinglish/Hindi/English me baat karo" type note add kar sako. Batao agar wo chahiye.

---

## Kya-kya extend kar sakte ho aage

- **Voice wake word** ("Hey Jimi") — Android SpeechRecognizer add karke
- **Auto-reply to incoming WhatsApp messages** — notification listener service se
- **Per-contact style auto-detect** — purane chat messages padh kar (WhatsApp chat export se) style seekh sakta hai
- **More apps** — same AppLauncher + Accessibility pattern kisi bhi app ke liye extend hota hai (Instagram DM automate karna, Gmail automate karna, etc.)

Agar in me se koi feature abhi add karwana ho, bata dena — isi project me add kar dunga.
