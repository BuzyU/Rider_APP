import os, re
d = r'c:\Users\Umer Zingu\Desktop\Learning\Rider_APP\mobile-app\app\src\main\java\com\ridervoice\ui\screens'
skip = ['SettingsScreen.kt', 'JoinRoomScreen.kt', 'RideReplayScreen.kt', 'HeadsetSettingsScreen.kt']
for f in os.listdir(d):
    if f.endswith('.kt') and f not in skip:
        p = os.path.join(d, f)
        with open(p, 'r', encoding='utf-8') as file:
            c = file.read()
        
        # Replacements
        c = re.sub(r'color\s*=\s*Color\.White', 'color = TextPrimary', c)
        c = re.sub(r'tint\s*=\s*Color\.White', 'tint = TextPrimary', c)
        c = re.sub(r'textColor\s*=\s*Color\.White', 'textColor = TextPrimary', c)
        c = re.sub(r'textColor\s*=\s*Color\.Black', 'textColor = TextPrimary', c)
        c = re.sub(r'color\s*=\s*Color\.Black', 'color = TextPrimary', c)
        
        c = re.sub(r'background\(Color\.Black\)', 'background(GraphiteBase)', c)
        c = re.sub(r'background\(Color\.White\)', 'background(GraphiteBase)', c)
        
        c = re.sub(r'Color\(0xFF0A0E14\)', 'GraphiteBase', c)
        c = re.sub(r'Color\(0xFF0D1520\)', 'GraphiteBase', c)
        c = re.sub(r'Color\(0xFF151A20\)', 'DarkSlate', c)
        c = re.sub(r'Color\(0xFF1D232B\)', 'Gunmetal', c)
        c = re.sub(r'Color\(0xFF2B3138\)', 'BorderColor', c)
        c = re.sub(r'Color\(0xFF8B0000\)', 'AlertRed', c)
        c = re.sub(r'Color\(0xFF34C759\)', 'SuccessGreen', c)
        
        c = re.sub(r'Color\(0x[E-F][E-F]000000\)', 'GraphiteBase.copy(alpha = 0.9f)', c)
        c = re.sub(r'Color\(0x[A-D][A-D]000000\)', 'DarkSlate.copy(alpha = 0.8f)', c)
        
        c = re.sub(r'contentColor\s*=\s*Color\.White', 'contentColor = TextPrimary', c)
        
        with open(p, 'w', encoding='utf-8') as file:
            file.write(c)
