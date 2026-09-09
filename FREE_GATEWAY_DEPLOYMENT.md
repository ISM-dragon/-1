# 🆓 نشر البوابة المجانية - دليل كامل

## المشكلة التي نحلها

البوابة الكاملة تحتاج:
- 4GB+ RAM
- GPU
- FFmpeg ثقيل
- لا تعمل على free tier → مشاكل

الحل: **بوابة مجانية خفيفة** تعمل على خدمات مجانية بدون مشاكل!

## ✅ البوابة المجانية - المميزات

- **512MB RAM فقط** - تعمل على free tier
- **لا تحتاج GPU** - تستخدم Gemini API
- **Mock مقاطع للاختبار** - 3 مقاطع تجريبية تلقائياً
- **نفس API** - نفس endpoints، نفس التطبيق
- **تستيقظ تلقائياً** - حتى لو نامت على Render

## 🚀 النشر - 3 خيارات مجانية

### 1. Fly.io (الأفضل - موصى به)

**المميزات:**
- 3 VMs مجانية (shared-cpu-1x 256MB)
- 160GB egress مجاني
- لا تنام - تعمل دائماً
- سريعة (Frankfurt, Amsterdam)

**الخطوات:**

```bash
# 1. تثبيت flyctl
curl -L https://fly.io/install.sh | sh
# أو على Windows: powershell -Command "iwr https://fly.io/install.ps1 -useb | iex"

# 2. تسجيل دخول
flyctl auth login

# 3. نشر
cd gateway_free
flyctl launch --name ism-free-gateway --region ams --no-deploy
# اختر: No Postgres, No Redis

flyctl deploy

# 4. ستحصل على URL:
# https://ism-free-gateway.fly.dev

# 5. اختبار
curl https://ism-free-gateway.fly.dev/health
curl https://ism-free-gateway.fly.dev/v1/gateway/discovery
```

**التكلفة:** $0 مجاني 100%

---

### 2. Render (سهل جداً)

**المميزات:**
- 750 ساعة/شهر مجانية
- سهل - ربط GitHub فقط
- تنام بعد 15 دقيقة (تستيقظ تلقائياً عند الطلب)

**الخطوات:**

1. اذهب إلى https://render.com وسجل دخول بـ GitHub
2. New → Web Service
3. Connect repository: `ISM-dragon/-1`
4. الإعدادات:
   - **Name:** `ism-free-gateway`
   - **Region:** Frankfurt
   - **Branch:** `main` أو `arena/01a08466-1`
   - **Root Directory:** `gateway_free`
   - **Runtime:** Python 3
   - **Build Command:** `pip install -r requirements.txt`
   - **Start Command:** `uvicorn main:app --host 0.0.0.0 --port $PORT`
   - **Plan:** Free
5. Environment Variables:
   - `PUBLIC_BASE_URL` = `https://ism-free-gateway.onrender.com`
   - `FREE_MODE` = `true`
   - `PROVIDER_MODE` = `mock`
6. Create Web Service
7. ستحصل على: `https://ism-free-gateway.onrender.com`

**ملاحظة:** أول طلب بعد النوم يأخذ 30-60 ثانية (تستيقظ)

---

### 3. Railway (سريع)

```bash
npm i -g @railway/cli
railway login
cd gateway_free
railway init
railway up
# ستحصل على URL مجاني مع $5 credit
```

---

### 4. HuggingFace Spaces (مجاني)

1. https://huggingface.co/new-space
2. Space name: `ism-free-gateway`
3. License: AGPL-3.0
4. Template: Docker
5. ارفع ملفات `gateway_free/`
6. سيعمل تلقائياً على `https://ism-free-gateway-free.hf.space`

---

### 5. Koyeb (بديل)

- https://koyeb.com - Free tier: 2 services, 512MB RAM

---

## 📱 إعداد Android

### تلقائي (موصى به)

التطبيق يكتشف البوابة المجانية تلقائياً:

1. افتح التطبيق
2. سيحاول بالترتيب:
   - `https://ism-free-gateway.fly.dev` ← مجانية
   - `https://ism-free-gateway.onrender.com` ← مجانية
   - `https://api.ism.app`
   - `http://10.0.2.2:8787` (emulator)
   - `http://192.168.1.100:8787` (شبكة محلية)
3. إذا وجد واحدة صحية → يحفظها تلقائياً → لا حاجة لإدخال رابط!

### يدوي

الإعدادات → بوابات مجانية جاهزة 🆓 → اختر واحدة → اختبار

### QR Code

لوحة التحكم `/dashboard` → QR code → امسح من التطبيق (قريباً)

## 🔧 API - نفس البوابة الكاملة

```bash
# Health - بدون auth
curl https://ism-free-gateway.fly.dev/health

# Discovery - بدون auth - للـ auto-discovery
curl https://ism-free-gateway.fly.dev/v1/gateway/discovery

# Config - بدون auth
curl https://ism-free-gateway.fly.dev/v1/gateway/config

# Dashboard - HTML جميل
open https://ism-free-gateway.fly.dev/dashboard

# إنشاء مهمة
curl -X POST https://ism-free-gateway.fly.dev/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{"title":"فيديو تجريبي","mode":"balanced"}'

# حالة المهمة
curl https://ism-free-gateway.fly.dev/v1/jobs/{id}

# النتائج - 3 مقاطع mock تلقائياً
curl https://ism-free-gateway.fly.dev/v1/jobs/{id}/result

# رفع فيديو
curl -X POST https://ism-free-gateway.fly.dev/v1/media/upload \
  -F "file=@video.mp4"

# ربط حساب (mock)
curl -X POST https://ism-free-gateway.fly.dev/v1/social/tiktok/connect

# الحسابات
curl https://ism-free-gateway.fly.dev/v1/social/accounts
```

## 🆚 Free vs Full

| الميزة | Free (هذا) | Full |
|--------|-----------|------|
| RAM | 512MB ✅ | 4GB+ |
| GPU | لا يحتاج ✅ | يحتاج |
| يعمل على free tier | ✅ نعم | ❌ لا |
| WhisperX | لا (Gemini API) | نعم محلي |
| Diarization | لا | نعم |
| Face Detection | لا | نعم |
| مقاطع حقيقية | Mock (3 تجريبية) | حقيقية |
| للاختبار | ✅ ممتاز | ❌ ثقيل |
| للإنتاج | ⚠️ محدود | ✅ احترافي |
| التكلفة | $0 | $5-20/شهر VPS |

## 🎯 متى تستخدم ماذا؟

**استخدم Free إذا:**
- تريد تجربة التطبيق بسرعة
- لا تريد إعداد خادم
- للاختبار والتطوير
- ميزانية صفر

**استخدم Full إذا:**
- تريد معالجة احترافية حقيقية
- WhisperX + diarization + face detection
- مقاطع حقيقية وليس mock
- للإنتاج الحقيقي

**الحل المثالي:**
1. جرب Free الآن (2 دقيقة نشر)
2. إذا أعجبك → انشر Full على VPS رخيص:
   - Hetzner: €4.15/شهر (2GB RAM)
   - DigitalOcean: $6/شهر
   - Contabo: €4.50/شهر (4GB RAM)

## 🔐 الأمان

Free gateway:
- `REQUIRE_GATEWAY_TOKEN=false` افتراضياً (للتجربة)
- للإنتاج: ضع `GATEWAY_TOKEN` قوي و `REQUIRE_GATEWAY_TOKEN=true`
- لا يحفظ API keys في الكود
- HTTPS تلقائي على Fly.io/Render

## 📊 المراقبة

لوحة التحكم `/dashboard` تظهر:
- عدد المهام والمقاطع
- حالة البوابة
- معلومات الاتصال
- تعليمات النشر

## 🐛 حل المشاكل

**البوابة لا تستجيب:**
- Render تنام بعد 15 دقيقة → أول طلب يأخذ 60 ثانية (طبيعي)
- Fly.io لا تنام → إذا فشلت، تحقق من logs: `flyctl logs`

**التطبيق لا يجد البوابة:**
- الإعدادات → اختر بوابة مجانية يدوياً
- جرب `https://ism-free-gateway.fly.dev` مباشرة
- تحقق من الإنترنت

**Mock مقاطع فقط:**
- هذا طبيعي في Free mode
- للمعالجة الحقيقية: انشر Full gateway أو أضف `GEMINI_API_KEY`

## 🚀 نشر Full Gateway (للإنتاج)

```bash
# على VPS
git clone https://github.com/ISM-dragon/-1.git
cd -1
docker-compose -f docker-compose.gateway.yml up -d

# أو
docker build -f Dockerfile.gateway -t ism-gateway .
docker run -d -p 8787:8787 \
  -e GATEWAY_TOKEN=your_strong_token \
  -e GEMINI_API_KEY=your_key \
  -e PUBLIC_BASE_URL=https://your-domain.com \
  ism-gateway
```

## 📝 ملاحظات

- Free gateway تستخدم SQLite (ملف واحد) - بسيطة
- لا تحتاج FFmpeg ثقيل
- تعمل حتى على Raspberry Pi!
- مثالية للـ demo والـ portfolio

## 🎉 الخلاصة

**بـ 2 دقيقة:**
```bash
cd gateway_free
flyctl deploy
# → https://ism-free-gateway.fly.dev جاهزة!
```

**التطبيق:**
- يفتح → يكتشف تلقائياً → يعمل → لا مشاكل! 🆓✅

---

**الآن جرب:** https://ism-free-gateway.fly.dev/dashboard (بعد النشر)
