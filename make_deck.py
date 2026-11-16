#!/usr/bin/env python3
"""Build a beginner-friendly step-by-step deployment deck for Contact Repair."""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR

# ---- palette (matches app brand #0B57D0) ----
BLUE   = RGBColor(0x0B, 0x57, 0xD0)
DARK   = RGBColor(0x1A, 0x2B, 0x4A)
WHITE  = RGBColor(0xFF, 0xFF, 0xFF)
GREY   = RGBColor(0x5A, 0x64, 0x73)
LIGHT  = RGBColor(0xEE, 0xF3, 0xFB)
GREEN  = RGBColor(0x1E, 0x8E, 0x5A)
AMBER  = RGBColor(0xC9, 0x8A, 0x00)

prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)
SW, SH = prs.slide_width, prs.slide_height
BLANK = prs.slide_layouts[6]

def add_slide():
    return prs.slides.add_slide(BLANK)

def rect(slide, x, y, w, h, color, line=None):
    from pptx.enum.shapes import MSO_SHAPE
    shp = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, x, y, w, h)
    shp.fill.solid(); shp.fill.fore_color.rgb = color
    if line is None:
        shp.line.fill.background()
    else:
        shp.line.color.rgb = line; shp.line.width = Pt(1)
    shp.shadow.inherit = False
    return shp

def txbox(slide, x, y, w, h, text, size=18, color=DARK, bold=False,
         align=PP_ALIGN.LEFT, font="Calibri", anchor=MSO_ANCHOR.TOP):
    tb = slide.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame
    tf.word_wrap = True
    tf.vertical_anchor = anchor
    lines = text.split("\n")
    for i, ln in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        r = p.add_run(); r.text = ln
        r.font.size = Pt(size); r.font.bold = bold
        r.font.color.rgb = color; r.font.name = font
    return tb

def bullets(slide, x, y, w, h, items, size=16, color=DARK, gap=6,
            font="Calibri", marker="•", marker_color=None):
    tb = slide.shapes.add_textbox(x, y, w, h)
    tf = tb.text_frame; tf.word_wrap = True
    marker_color = marker_color or BLUE
    for i, item in enumerate(items):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.space_after = Pt(gap)
        if marker:
            rm = p.add_run(); rm.text = marker + "  "
            rm.font.size = Pt(size); rm.font.bold = True
            rm.font.color.rgb = marker_color; rm.font.name = font
        r = p.add_run(); r.text = item
        r.font.size = Pt(size); r.font.color.rgb = color; r.font.name = font
    return tb

def header(slide, title, subtitle=None, num=None):
    rect(slide, 0, 0, SW, Inches(1.15), BLUE)
    txbox(slide, Inches(0.55), Inches(0.18), Inches(10.5), Inches(0.85),
          title, size=30, color=WHITE, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    if num is not None:
        c = rect(slide, Inches(11.7), Inches(0.28), Inches(1.15), Inches(0.6), WHITE)
        tf = c.text_frame; tf.word_wrap = False
        p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
        r = p.add_run(); r.text = str(num)
        r.font.size = Pt(24); r.font.bold = True; r.font.color.rgb = BLUE
    if subtitle:
        txbox(slide, Inches(0.55), Inches(1.25), Inches(12.2), Inches(0.5),
              subtitle, size=15, color=GREY)

def footer(slide):
    rect(slide, 0, SH - Inches(0.32), SW, Inches(0.32), LIGHT)
    txbox(slide, Inches(0.55), SH - Inches(0.31), Inches(12), Inches(0.3),
          "Contact Repair  ·  Step-by-step deployment guide", size=10, color=GREY)

def code_box(slide, x, y, w, h, text, size=13, color=DARK):
    rect(slide, x, y, w, h, RGBColor(0xF4, 0xF6, 0xF9))
    tb = slide.shapes.add_textbox(x + Inches(0.2), y + Inches(0.12),
                                  w - Inches(0.4), h - Inches(0.24))
    tf = tb.text_frame; tf.word_wrap = True
    for i, ln in enumerate(text.split("\n")):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        r = p.add_run(); r.text = ln
        r.font.size = Pt(size); r.font.name = "Consolas"
        r.font.color.rgb = RGBColor(0x1F, 0x2D, 0x3D)
    return tb

# ============================================================
# 1. TITLE
# ============================================================
s = add_slide()
rect(s, 0, 0, SW, SH, DARK)
rect(s, 0, Inches(4.7), SW, Inches(2.8), BLUE)
txbox(s, Inches(0.9), Inches(1.7), Inches(11.5), Inches(1.4),
      "Deploying Contact Repair", size=52, color=WHITE, bold=True)
txbox(s, Inches(0.9), Inches(3.0), Inches(11.5), Inches(1.0),
      "A beginner-friendly, step-by-step guide to taking the app to Google Play",
      size=22, color=RGBColor(0xC9, 0xD8, 0xF5))
txbox(s, Inches(0.95), Inches(5.1), Inches(11.5), Inches(1.2),
      "No prior experience needed. Follow the numbered steps in order.\n"
      "Everything is already built and tested — you are just publishing it.",
      size=17, color=WHITE)

# ============================================================
# 2. WHAT IS THIS PROJECT
# ============================================================
s = add_slide()
header(s, "What is this project?")
bullets(s, Inches(0.7), Inches(1.7), Inches(11.9), Inches(5.2), [
    "Contact Repair is a phone/tablet Android app that fixes broken or messy contact files (vCard, CSV, text) right on the device.",
    "The user picks files with the normal Android file picker. The app repairs them and saves one .zip file the user can import into Google Contacts.",
    "The .zip contains: contacts.vcf (a standard vCard), google-contacts.csv, and repair-report.txt (a summary of what was fixed).",
    "It makes NO internet calls, asks for NO contacts permission, and has NO account. All processing happens in memory on the phone.",
    "Good news: the app is already coded, compiled, tested (14/14 tests pass), and packaged. Your job is to publish it to Google Play.",
], size=18, gap=12)
footer(s)

# ============================================================
# 3. WHAT YOU ALREADY HAVE
# ============================================================
s = add_slide()
header(s, "What you already have (no work needed here)")
rows = [
    ("Release app bundle (.aab)", "app/build/outputs/bundle/release/app-release.aab"),
    ("Release APK", "app/build/outputs/apk/release/app-release.apk"),
    ("Upload signing key", "secrets/upload-keystore.jks"),
    ("Signing credentials", "secrets/keystore.properties"),
    ("512px store icon", "app/src/main/play/.../graphics/icon/icon-512.png"),
    ("Store listing text", "PLAYSTORE.md"),
    ("Project readme", "README.md"),
]
y = Inches(1.55)
for label, path in rows:
    rect(s, Inches(0.7), y, Inches(4.6), Inches(0.62), LIGHT)
    txbox(s, Inches(0.9), y, Inches(4.4), Inches(0.62), label, size=15,
          color=DARK, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    rect(s, Inches(5.4), y, Inches(7.2), Inches(0.62), RGBColor(0xF4, 0xF6, 0xF9))
    txbox(s, Inches(5.6), y, Inches(7.0), Inches(0.62), path, size=14,
          color=RGBColor(0x1F, 0x2D, 0x3D), font="Consolas", anchor=MSO_ANCHOR.MIDDLE)
    y += Inches(0.74)
txbox(s, Inches(0.7), Inches(6.9), Inches(12), Inches(0.4),
      "All paths are relative to the project folder: /home/user/contact-repair",
      size=13, color=GREY)
footer(s)

# ============================================================
# 4. THE ROADMAP (overview of all steps)
# ============================================================
s = add_slide()
header(s, "The road to the Play Store — overview")
steps = [
    ("1", "Back up your signing key"),
    ("2", "Host your privacy policy online"),
    ("3", "Set your real support email"),
    ("4", "Take 3 phone screenshots"),
    ("5", "Create a Google Play Console account"),
    ("6", "Create the app in the console"),
    ("7", "Fill in the store listing"),
    ("8", "Answer the data-safety questions"),
    ("9", "Do the content-rating questionnaire"),
    ("10", "Upload the .aab + enroll in Play App Signing"),
    ("11", "Submit for review and wait"),
]
x0, y0 = Inches(0.7), Inches(1.7)
colw = Inches(5.9); rowh = Inches(0.86)
for i, (n, t) in enumerate(steps):
    col = i // 6
    row = i % 6
    x = x0 + col * colw
    y = y0 + row * rowh
    c = rect(s, x, y, Inches(0.6), Inches(0.6), BLUE)
    tf = c.text_frame; p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
    r = p.add_run(); r.text = n; r.font.size = Pt(18); r.font.bold = True
    r.font.color.rgb = WHITE
    txbox(s, x + Inches(0.75), y, colw - Inches(0.8), Inches(0.7),
          t, size=15, color=DARK, anchor=MSO_ANCHOR.MIDDLE)
footer(s)

# ============================================================
# 5. STEP 1 - BACKUP KEYS
# ============================================================
s = add_slide()
header(s, "Step 1 — Back up your signing key FIRST", num=1)
rect(s, Inches(0.7), Inches(1.6), Inches(11.9), Inches(0.7), RGBColor(0xFF, 0xF3, 0xD6))
txbox(s, Inches(0.9), Inches(1.62), Inches(11.5), Inches(0.66),
      "⚠  This is the single most important step. Do it before anything else.",
      size=17, color=AMBER, bold=True, anchor=MSO_ANCHOR.MIDDLE)
bullets(s, Inches(0.7), Inches(2.55), Inches(11.9), Inches(4.2), [
    "Two files make the app yours forever: secrets/upload-keystore.jks and secrets/keystore.properties.",
    "If you lose the .jks file after enrolling in Play App Signing, you can only recover it through Google's upload-key reset — and only for future updates, not old ones.",
    "Copy BOTH files to at least two safe places: a password manager (1Password, Bitwarden), an encrypted external drive, and an email to yourself.",
    "Never put the password inside keystore.properties into a public website, git repo, or chat.",
    "Write down the key alias (it is 'upload') and where you stored the password. The password itself stays private.",
], size=17, gap=12)
footer(s)

# ============================================================
# 6. STEP 2 - PRIVACY POLICY
# ============================================================
s = add_slide()
header(s, "Step 2 — Host your privacy policy online", num=2)
bullets(s, Inches(0.7), Inches(1.6), Inches(11.9), Inches(3.2), [
    "Google Play requires a public, stable privacy-policy URL. The full text is already written in PLAYSTORE.md under 'Privacy Policy'.",
    "You need a place to put it on the web. Easy free options: a free page on GitHub Pages, a file on Netlify, or a simple page on your own website.",
    "Copy the 'Privacy Policy' section from PLAYSTORE.md into that page and publish it. Save the resulting URL (it must be reachable without a login).",
    "You will paste this URL into the Play Console in Step 7.",
], size=17, gap=12)
code_box(s, Inches(0.7), Inches(4.9), Inches(11.9), Inches(1.5),
         "Example final URL you will create:\n"
         "https://yourname.github.io/contact-repair/privacy.html\n\n"
         "(Any public URL works — just make sure the text matches PLAYSTORE.md.)",
         size=14)
footer(s)

# ============================================================
# 7. STEP 3 - SUPPORT EMAIL
# ============================================================
s = add_slide()
header(s, "Step 3 — Set your real support email", num=3)
bullets(s, Inches(0.7), Inches(1.6), Inches(11.9), Inches(3.4), [
    "PLAYSTORE.md currently has the placeholder  support@example.com  under 'Contact Email'.",
    "Replace it with a real email address users can write to if they have a problem (a support@... address you actually check).",
    "The same email (or your Google account email) is used in the Play Console as the 'developer contact'.",
    "This is the ONLY placeholder you are expected to change. Every other fact in PLAYSTORE.md is final.",
], size=17, gap=12)
code_box(s, Inches(0.7), Inches(5.1), Inches(11.9), Inches(1.3),
         "In PLAYSTORE.md, find this line and replace it:\n"
         "    support@example.com\n"
         "with your real support email, e.g.  support@yourcompany.com",
         size=14)
footer(s)

# ============================================================
# 8. STEP 4 - SCREENSHOTS
# ============================================================
s = add_slide()
header(s, "Step 4 — Take 3 phone screenshots", num=4)
txbox(s, Inches(0.7), Inches(1.55), Inches(11.9), Inches(0.6),
      "You need at least 2 and up to 8 screenshots. Capture exactly these 3 screens on a real phone (or an emulator):",
      size=16, color=DARK)
cards = [
    ("Screen 1 — Repair", "The first screen: the 'Choose contact files' button, the country dropdown, and the three option switches."),
    ("Screen 2 — Review", "The list of repaired contacts with the counts at the top and the warning dots next to names."),
    ("Screen 3 — Save", "The 'Save repaired contacts' button and the import instructions shown to the user."),
]
x = Inches(0.7)
for title, desc in cards:
    rect(s, x, Inches(2.35), Inches(3.8), Inches(3.3), LIGHT)
    rect(s, x, Inches(2.35), Inches(3.8), Inches(0.6), BLUE)
    txbox(s, x, Inches(2.35), Inches(3.8), Inches(0.6), title, size=16,
          color=WHITE, bold=True, align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    txbox(s, x + Inches(0.2), Inches(3.1), Inches(3.4), Inches(2.4), desc,
          size=14, color=DARK)
    x += Inches(4.05)
txbox(s, Inches(0.7), Inches(5.9), Inches(11.9), Inches(1.0),
      "Tip: use the phone's native screenshot button. Phone screenshots are ~9:16 portrait. "
      "This environment did not run an emulator, so you must capture them yourself on a device.",
      size=15, color=GREY)
footer(s)

# ============================================================
# 9. STEP 5 - GOOGLE PLAY CONSOLE ACCOUNT
# ============================================================
s = add_slide()
header(s, "Step 5 — Create a Google Play Console account", num=5)
bullets(s, Inches(0.7), Inches(1.6), Inches(11.9), Inches(4.6), [
    "Go to  play.google.com/console  and sign in with a Google account (your developer account).",
    "Accept the terms. Google charges a one-time $25 developer registration fee. You need a billing profile (a card) to pay it.",
    "Choose a 'developer name' — this is what users see under the app on the store (e.g. your name or company name). Pick it carefully; it is hard to change later.",
    "Complete the developer identity form: your legal name, address, and country. For an individual, use your own details; for a company, use the company's.",
    "Once the account is active you will land in the Play Console dashboard. That is where all the remaining steps happen.",
], size=17, gap=12)
footer(s)

# ============================================================
# 10. STEP 6 - CREATE THE APP
# ============================================================
s = add_slide()
header(s, "Step 6 — Create the app in the console", num=6)
bullets(s, Inches(0.7), Inches(1.6), Inches(11.9), Inches(4.6), [
    "In the dashboard, click  'Create app'.",
    "Enter the app name:  Contact Repair",
    "Default language:  English (United States)",
    "Type:  Application  (not 'Add-on').",
    "Click 'Create'. The console now shows a set of tabs on the left: Store presence, Content, Release, Testing, Users and permissions, and Finance.",
    "You will work through Store presence, Content, and Release over the next steps. Leave the others at their defaults for now.",
], size=17, gap=12)
footer(s)

# ============================================================
# 11. STEP 7 - STORE LISTING
# ============================================================
s = add_slide()
header(s, "Step 7 — Fill in the store listing", num=7)
rows = [
    ("Short description", "Fix broken contact files on your phone. Nothing is uploaded."),
    ("Full description", "Paste the 'Full Description' section from PLAYSTORE.md."),
    ("Category", "Tools"),
    ("App icon (512px)", "Upload  app/src/main/play/.../graphics/icon/icon-512.png"),
    ("Phone screenshots", "Upload the 3 screenshots you took in Step 4."),
    ("Privacy policy URL", "Paste the URL you created in Step 2."),
    ("Contact email", "Your real support email from Step 3."),
]
y = Inches(1.55)
for label, val in rows:
    rect(s, Inches(0.7), y, Inches(3.4), Inches(0.66), LIGHT)
    txbox(s, Inches(0.9), y, Inches(3.2), Inches(0.66), label, size=14,
          color=DARK, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    rect(s, Inches(4.2), y, Inches(8.4), Inches(0.66), RGBColor(0xF4, 0xF6, 0xF9))
    txbox(s, Inches(4.4), y, Inches(8.1), Inches(0.66), val, size=13,
          color=RGBColor(0x1F, 0x2D, 0x3D), anchor=MSO_ANCHOR.MIDDLE)
    y += Inches(0.74)
txbox(s, Inches(0.7), Inches(6.95), Inches(12), Inches(0.4),
      "These are all under  Store presence → Store listing.  Save when done.",
      size=13, color=GREY)
footer(s)

# ============================================================
# 12. STEP 8 - DATA SAFETY
# ============================================================
s = add_slide()
header(s, "Step 8 — Answer the data-safety questions", num=8)
txbox(s, Inches(0.7), Inches(1.55), Inches(11.9), Inches(0.5),
      "Go to  Store presence → Data safety.  This app is very privacy-friendly, so the answers are simple and honest:",
      size=16, color=DARK)
bullets(s, Inches(0.7), Inches(2.2), Inches(11.9), Inches(4.4), [
    "Data collected:  None. The app does not collect any data.",
    "Data shared with third parties:  None.",
    "Contact data from user files:  Processed locally on the device; not collected and not transmitted by the developer.",
    "Analytics:  Not used.",
    "Advertising ID:  Not accessed.",
    "Because nothing is collected, most 'why we collect it' boxes can be marked 'not collected'. Answer exactly as described in PLAYSTORE.md under 'Data Safety Form Answers'.",
], size=16, gap=11)
footer(s)

# ============================================================
# 13. STEP 9 - CONTENT RATING
# ============================================================
s = add_slide()
header(s, "Step 9 — Do the content-rating questionnaire", num=9)
bullets(s, Inches(0.7), Inches(1.6), Inches(11.9), Inches(4.6), [
    "Go to  Content → Content rating.  Answer the questionnaire for your app's category and country.",
    "This app is clean: no violence, no social features, no user-generated public sharing, no unrestricted web view, not a dating app.",
    "It is not directed at children (13+).",
    "Google will compute an age rating from your answers. Do not guess a letter yourself — just answer the questions truthfully and use the result.",
    "The guidance for each answer is written in PLAYSTORE.md under 'Content Rating'.",
], size=17, gap=12)
footer(s)

# ============================================================
# 14. STEP 10 - UPLOAD AAB + PLAY APP SIGNING
# ============================================================
s = add_slide()
header(s, "Step 10 — Upload the .aab + Play App Signing", num=10)
bullets(s, Inches(0.7), Inches(1.6), Inches(11.9), Inches(4.7), [
    "Go to  Release → Production → Create new release.",
    "Upload the app bundle file:  app/build/outputs/bundle/release/app-release.aab",
    "Add a release name (e.g. '1.0.0') and a short description of the first version.",
    "On first release, Google asks about  Play App Signing. Choose to enroll. Google keeps a master key; you keep using your upload key (secrets/upload-keystore.jks) to sign each upload.",
    "Google will verify your upload key against the one already built into the .aab — they match because the app was signed with it during the build.",
    "Target API is 36 (Android 16), which meets Play's requirement for new apps as of August 31, 2026. No extra action needed.",
], size=16, gap=11)
footer(s)

# ============================================================
# 15. STEP 11 - SUBMIT FOR REVIEW
# ============================================================
s = add_slide()
header(s, "Step 11 — Submit for review and wait", num=11)
bullets(s, Inches(0.7), Inches(1.6), Inches(11.9), Inches(4.6), [
    "Before submitting, the console shows a checklist. Make sure every item is green: listing, screenshots, data safety, content rating, and a uploaded bundle.",
    "Choose your rollout. For a first release, 'Production' (all users) or a small 'Closed testing' track both work. Production is simplest.",
    "Click  'Start rollout to production' (or send to your chosen track).",
    "Google reviews new apps in roughly 1–3 days. You will get an email when it is approved, rejected, or needs changes.",
    "If rejected, the email explains exactly what to fix. Update the listing or the bundle, then re-submit.",
    "Once approved, the app goes live on the Play Store at the URL shown in the console.",
], size=17, gap=12)
footer(s)

# ============================================================
# 16. AFTER APPROVAL / UPDATES
# ============================================================
s = add_slide()
header(s, "After approval — making future updates")
bullets(s, Inches(0.7), Inches(1.6), Inches(11.9), Inches(4.6), [
    "To ship a new version, rebuild the app and upload the new .aab the same way (Release → Production → Create new release).",
    "Keep signing every upload with the same secrets/upload-keystore.jks. Never generate a different key — the store will reject a mismatch.",
    "Bump the version number (versionCode) for every new build so the store treats it as an update.",
    "You can manage testers, view download/quality reports, and respond to reviews from the console at any time.",
    "Keep your two backup copies of the keystore safe forever — they are the only thing that can prove the app is yours.",
], size=17, gap=12)
footer(s)

# ============================================================
# 17. GLOSSARY
# ============================================================
s = add_slide()
header(s, "Quick glossary (words you will see)")
terms = [
    (".aab", "Android App Bundle — the file format Google Play wants you to upload."),
    (".apk", "A single installable Android package. Useful for testing on one device; not what you upload to Play."),
    ("Upload key", "Your private signing key (the .jks file). You sign uploads with it; Google signs the public copy."),
    ("Play App Signing", "Google's system that signs the app for distribution. You enroll once at first release."),
    ("SAF", "Storage Access Framework — the system file picker/creator the app uses. No storage permission needed."),
    ("vCard (.vcf)", "A standard text format for contacts. The app outputs vCard 3.0."),
    ("targetSdk / API 36", "The Android version the app is built against. 36 = Android 16, which Play now requires."),
]
y = Inches(1.55)
for term, defn in terms:
    rect(s, Inches(0.7), y, Inches(2.7), Inches(0.66), LIGHT)
    txbox(s, Inches(0.85), y, Inches(2.5), Inches(0.66), term, size=14,
          color=BLUE, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    txbox(s, Inches(3.6), y, Inches(9.0), Inches(0.66), defn, size=14,
          color=DARK, anchor=MSO_ANCHOR.MIDDLE)
    y += Inches(0.74)
footer(s)

# ============================================================
# 18. TROUBLESHOOTING
# ============================================================
s = add_slide()
header(s, "Troubleshooting — if something goes wrong")
rows = [
    ("'App signing key does not match'", "You uploaded a bundle signed with a different key. Rebuild with secrets/upload-keystore.jks and re-upload."),
    ("'Missing privacy policy'", "Step 7 needs a valid, public URL from Step 2. Make sure the page loads without a login."),
    ("Review rejected for permissions", "This app declares none. If Play flags a permission, check you uploaded the right .aab from this project."),
    ("Screenshots rejected", "Use portrait phone screenshots (about 9:16). At least 2, up to 8."),
    ("Cannot pay the $25 fee", "The developer account needs a valid billing card. Add one in the console's Finance section."),
    ("Lost the keystore", "Stop. Use Google's upload-key reset only for future updates, and restore your backups. Prevent this with Step 1."),
]
y = Inches(1.55)
for q, a in rows:
    rect(s, Inches(0.7), y, Inches(4.3), Inches(0.72), LIGHT)
    txbox(s, Inches(0.85), y, Inches(4.0), Inches(0.72), q, size=13,
          color=DARK, bold=True, anchor=MSO_ANCHOR.MIDDLE)
    rect(s, Inches(5.1), y, Inches(7.5), Inches(0.72), RGBColor(0xF4, 0xF6, 0xF9))
    txbox(s, Inches(5.25), y, Inches(7.2), Inches(0.72), a, size=12.5,
          color=RGBColor(0x1F, 0x2D, 0x3D), anchor=MSO_ANCHOR.MIDDLE)
    y += Inches(0.82)
footer(s)

# ============================================================
# 19. FINAL CHECKLIST
# ============================================================
s = add_slide()
header(s, "Final checklist — tick these before you hit submit")
items = [
    "Backed up  secrets/upload-keystore.jks  and  secrets/keystore.properties  in 2+ safe places",
    "Privacy policy is live at a public URL (from Step 2)",
    "support@example.com replaced with a real support email (Step 3)",
    "3 phone screenshots captured (Step 4)",
    "Google Play Console account created and $25 fee paid (Step 5)",
    "App 'Contact Repair' created in the console (Step 6)",
    "Store listing, icon, screenshots, privacy URL, and contact email filled in (Step 7)",
    "Data-safety questions answered (Step 8)",
    "Content-rating questionnaire completed (Step 9)",
    ".aab uploaded and Play App Signing enrolled (Step 10)",
    "Release submitted for review (Step 11)",
]
y = Inches(1.5)
for i, it in enumerate(items):
    col = 0 if i < 6 else 1
    row = i if i < 6 else i - 6
    x = Inches(0.7) + col * Inches(6.0)
    yy = Inches(1.55) + row * Inches(0.92)
    box = rect(s, x, yy, Inches(0.34), Inches(0.34), WHITE, line=BLUE)
    box.line.width = Pt(1.5)
    txbox(s, x + Inches(0.5), yy - Inches(0.06), Inches(5.4), Inches(0.85), it,
          size=13.5, color=DARK)
footer(s)

# ============================================================
# 20. CLOSING
# ============================================================
s = add_slide()
rect(s, 0, 0, SW, SH, DARK)
rect(s, 0, Inches(4.7), SW, Inches(2.8), BLUE)
txbox(s, Inches(0.9), Inches(2.1), Inches(11.5), Inches(1.2),
      "You are ready.", size=48, color=WHITE, bold=True)
txbox(s, Inches(0.95), Inches(5.1), Inches(11.5), Inches(1.3),
      "Follow the 11 numbered steps in order, keep your signing key safe, and submit.\n"
      "The app is built, tested, and ready — publishing is just the paperwork and the upload.",
      size=18, color=WHITE)

prs.save("/home/user/spacework/Contact-Repair-Deployment-Guide.pptx")
print("saved")
