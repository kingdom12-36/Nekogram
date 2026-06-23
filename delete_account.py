import os
import sys
import asyncio
from telethon import TelegramClient
from telethon.sessions import StringSession
from telethon.tl.functions.account import DeleteAccountRequest

# جلب البيانات من الـ Secrets الخاصة بـ GitHub بنفس الأسماء التي اخترتها
API_ID = os.getenv('TELEGRAM_API_ID')
API_HASH = os.getenv('TELEGRAM_API_HASH')
SESSION_STRING = os.getenv('TELEGRAM_CLIENT')

async def main():
    # التحقق من وجود المتغيرات
    if not all([API_ID, API_HASH, SESSION_STRING]):
        print("❌ خطأ: تأكد من إضافة جميع الـ Secrets في جيت هوب (TELEGRAM_API_ID, TELEGRAM_API_HASH, TELEGRAM_CLIENT)")
        sys.exit(1)
        
    try:
        api_id_int = int(API_ID)
    except ValueError:
        print("❌ خطأ: TELEGRAM_API_ID يجب أن يكون رقماً فقط.")
        sys.exit(1)

    print("⏳ جاري الاتصال بتيلجرام وفحص الجلسة...")
    client = TelegramClient(StringSession(SESSION_STRING), api_id_int, API_HASH)
    await client.connect()
    
    # التأكد من أن الجلسة نشطة ومصرحة
    if not await client.is_user_authorized():
        print("❌ خطأ قاتل: الجلسة (TELEGRAM_CLIENT) غير صالحة، منتهية، أو تم طردها من الحساب!")
        sys.exit(1)
        
    try:
        print("🚀 الجلسة نشطة! جاري إرسال طلب تدمير وحذف الحساب فوراً...")
        # إرسال طلب الحذف الرسمي
        await client(DeleteAccountRequest(reason="Automated deletion via GitHub Actions"))
        print("✅ تم حذف الحساب نهائياً وبنجاح واختفى من سيرفرات تيلجرام!")
        
    except Exception as e:
        print(f"❌ حدث خطأ أثناء محاولة الحذف: {e}")
    finally:
        await client.disconnect()

if __name__ == "__main__":
    asyncio.run(main())

