# ISM Free Gateway - مجاني للجميع

بوابة خفيفة مجانية لمعالجة الفيديوهات تعمل على الخدمات المجانية بدون مشاكل.

## 🆓 لماذا مجاني؟

الـ Gateway الكامل يحتاج:
- 4GB+ RAM
- GPU للـ WhisperX/Diarization
- FFmpeg ثقيل
- لا يعمل على free tier

الـ Free Gateway:
- 256-512MB RAM فقط
- لا يحتاج GPU
- يستخدم Gemini API للتحليل
- Mock مقاطع للاختبار
- يعمل على Fly.io, Render, Railway, HuggingFace مجاناً

## 🚀 النشر المجاني

### Fly.io (الأفضل - 3 VMs مجانية)

```bash
# تثبيت flyctl
curl -L https://fly.io/install.sh | sh

# تسجيل دخول
flyctl auth login

# نشر
cd gateway_free
flyctl launch --name ism-free-gateway --region ams --no-deploy
flyctl deploy

# ستحصل على: https://ism-free-gateway.fly.dev
```

### Render (سهل - 750 ساعة مجانية)

1. اذهب إلى https://render.com
2. New → Web Service
3. Connect repo `ISM-dragon/-1`
4. Root Directory: `gateway_free`
5. Build: `pip install -r requirements.txt`
6. Start: `uvicorn main:app --host 0.0.0.0 --port $PORT`
7. Env vars:
   - `PUBLIC_BASE_URL=https://ism-free-gateway.onrender.com`
   - `FREE_MODE=true`
8. Deploy → ستحصل على URL مجاني

### Railway ($5 credit مجاني)

```bash
npm i -g @railway/cli
railway login
railway init
railway up
```

### HuggingFace Spaces (مجاني مع Docker)

1. أنشئ Space جديد على https://huggingface.co/new-space
2. اختر Docker template
3. ارفع ملفات `gateway_free/`
4. سيعمل تلقائياً

### Docker محلي

```bash
cd gateway_free
docker build -t ism-free .
docker run -p 8787:8787 -e PUBLIC_BASE_URL=http://localhost:8787 ism-free
```

## 📱 للـ Android

التطبيق يكتشف Gateway تلقائياً:

1. **تلقائي:** يحاول `https://ism-free-gateway.fly.dev` ثم `https://ism-free-gateway.onrender.com` ثم الشبكة المحلية
2. **يدوي:** الإعدادات → متقدم → أدخل URL
3. **QR:** امسح QR code من لوحة التحكم `/dashboard`

## 🔧 API

```bash
# Health
curl https://ism-free-gateway.fly.dev/health

# Discovery (بدون auth)
curl https://ism-free-gateway.fly.dev/v1/gateway/discovery

# إنشاء مهمة
curl -X POST https://ism-free-gateway.fly.dev/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{"title":"فيديو تجريبي","mode":"balanced"}'

# النتائج
curl https://ism-free-gateway.fly.dev/v1/jobs/{id}/result
```

## 💡 Free vs Full

| الميزة | Free | Full |
|--------|------|------|
| RAM | 512MB | 4GB+ |
| GPU | لا | نعم |
| WhisperX | لا (Gemini) | نعم |
| Diarization | لا | نعم |
| Face Detection | لا | نعم |
| Mock مقاطع | نعم | لا (حقيقي) |
| Free tier | ✅ | ❌ |
| للاختبار | ✅ ممتاز | ❌ ثقيل |
| للإنتاج | ⚠️ محدود | ✅ احترافي |

## 🎯 للإنتاج الحقيقي

1. استخدم Free للاختبار والتطوير
2. انشر Full على VPS (Hetzner $5, DigitalOcean $6, etc.)
3. أو استخدم Free مع Gemini API key حقيقي للتحليل

## 📊 لوحة التحكم

افتح `/dashboard` لرؤية:
- الإحصائيات
- معلومات الاتصال
- API endpoints
- تعليمات النشر
