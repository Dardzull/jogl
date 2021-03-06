/*
 * Copyright (c) 2008 Sun Microsystems, Inc. All Rights Reserved.
 * Copyright (c) 2010 JogAmp Community. All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 * - Redistribution of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 * 
 * - Redistribution in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 * 
 * Neither the name of Sun Microsystems, Inc. or the names of
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 * 
 * This software is provided "AS IS," without a warranty of any kind. ALL
 * EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES,
 * INCLUDING ANY IMPLIED WARRANTY OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE OR NON-INFRINGEMENT, ARE HEREBY EXCLUDED. SUN
 * MICROSYSTEMS, INC. ("SUN") AND ITS LICENSORS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES. IN NO EVENT WILL SUN OR
 * ITS LICENSORS BE LIABLE FOR ANY LOST REVENUE, PROFIT OR DATA, OR FOR
 * DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL, INCIDENTAL OR PUNITIVE
 * DAMAGES, HOWEVER CAUSED AND REGARDLESS OF THE THEORY OF LIABILITY,
 * ARISING OUT OF THE USE OF OR INABILITY TO USE THIS SOFTWARE, EVEN IF
 * SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
 * 
 */

#include <Windows.h>
#include <Windowsx.h>
#include <tchar.h>
#include <stdlib.h>

// NOTE: it looks like SHFullScreen and/or aygshell.dll is not available on the APX 2500 any more
// #ifdef UNDER_CE
// #include "aygshell.h"
// #endif

#include <gluegen_stdint.h>

#if !defined(__MINGW64__) && _MSC_VER <= 1500
    // FIXME: Determine for which MSVC versions ..
    #define strdup(s) _strdup(s)
#endif

#ifndef WM_MOUSEWHEEL
#define WM_MOUSEWHEEL                   0x020A
#endif //WM_MOUSEWHEEL

#ifndef WM_MOUSEHWHEEL
#define WM_MOUSEHWHEEL                  0x020E
#endif //WM_MOUSEHWHEEL

#ifndef WHEEL_DELTAf
#define WHEEL_DELTAf                    (120.0f)
#endif //WHEEL_DELTAf

#ifndef WHEEL_PAGESCROLL
#define WHEEL_PAGESCROLL                (UINT_MAX)
#endif //WHEEL_PAGESCROLL

#ifndef GET_WHEEL_DELTA_WPARAM  // defined for (_WIN32_WINNT >= 0x0500)
#define GET_WHEEL_DELTA_WPARAM(wParam)  ((short)HIWORD(wParam))
#endif
#ifndef GET_KEYSTATE_WPARAM
#define GET_KEYSTATE_WPARAM(wParam)  ((short)LOWORD(wParam))
#endif

#ifndef WM_HSCROLL
#define WM_HSCROLL             0x0114
#endif
#ifndef WM_VSCROLL
#define WM_VSCROLL             0x0115
#endif

#ifndef WH_MOUSE
#define WH_MOUSE 7
#endif
#ifndef WH_MOUSE_LL
#define WH_MOUSE_LL 14
#endif

#ifndef MONITOR_DEFAULTTONULL
#define MONITOR_DEFAULTTONULL 0
#endif
#ifndef MONITOR_DEFAULTTOPRIMARY
#define MONITOR_DEFAULTTOPRIMARY 1
#endif
#ifndef MONITOR_DEFAULTTONEAREST
#define MONITOR_DEFAULTTONEAREST 2
#endif
#ifndef EDS_ROTATEDMODE
#define EDS_ROTATEDMODE 0x00000004
#endif
#ifndef DISPLAY_DEVICE_ACTIVE
#define DISPLAY_DEVICE_ACTIVE 0x00000001
#endif

#include "jogamp_newt_driver_windows_DisplayDriver.h"
#include "jogamp_newt_driver_windows_ScreenDriver.h"
#include "jogamp_newt_driver_windows_WindowDriver.h"

#include "Window.h"
#include "MouseEvent.h"
#include "InputEvent.h"
#include "KeyEvent.h"
#include "ScreenMode.h"

#include "NewtCommon.h"

// #define VERBOSE_ON 1
// #define DEBUG_KEYS 1

#ifdef VERBOSE_ON
    #define DBG_PRINT(...) fprintf(stderr, __VA_ARGS__); fflush(stderr) 
#else
    #define DBG_PRINT(...)
#endif

#define STD_PRINT(...) fprintf(stderr, __VA_ARGS__); fflush(stderr) 

static jmethodID insetsChangedID = NULL;
static jmethodID sizeChangedID = NULL;
static jmethodID positionChangedID = NULL;
static jmethodID focusChangedID = NULL;
static jmethodID visibleChangedID = NULL;
static jmethodID windowDestroyNotifyID = NULL;
static jmethodID windowRepaintID = NULL;
static jmethodID enqueueMouseEventID = NULL;
static jmethodID sendMouseEventID = NULL;
static jmethodID enqueueKeyEventID = NULL;
static jmethodID sendKeyEventID = NULL;
static jmethodID requestFocusID = NULL;

static RECT* UpdateInsets(JNIEnv *env, jobject window, HWND hwnd);

typedef struct {
    JNIEnv* jenv;
    jobject jinstance;
} WindowUserData;
    
typedef struct {
    UINT javaKey;
    UINT windowsKey;
} KeyMapEntry;

// Static table, arranged more or less spatially.
static KeyMapEntry keyMapTable[] = {
    // Modifier keys
    {J_VK_CAPS_LOCK,        VK_CAPITAL},
    {J_VK_SHIFT,            VK_SHIFT},
    {J_VK_CONTROL,          VK_CONTROL},
    {J_VK_ALT,              VK_MENU},
    {J_VK_NUM_LOCK,         VK_NUMLOCK},

    // Miscellaneous Windows keys
    {J_VK_WINDOWS,          VK_LWIN},
    {J_VK_WINDOWS,          VK_RWIN},
    {J_VK_CONTEXT_MENU,     VK_APPS},

    // Alphabet
    {J_VK_A,                'A'},
    {J_VK_B,                'B'},
    {J_VK_C,                'C'},
    {J_VK_D,                'D'},
    {J_VK_E,                'E'},
    {J_VK_F,                'F'},
    {J_VK_G,                'G'},
    {J_VK_H,                'H'},
    {J_VK_I,                'I'},
    {J_VK_J,                'J'},
    {J_VK_K,                'K'},
    {J_VK_L,                'L'},
    {J_VK_M,                'M'},
    {J_VK_N,                'N'},
    {J_VK_O,                'O'},
    {J_VK_P,                'P'},
    {J_VK_Q,                'Q'},
    {J_VK_R,                'R'},
    {J_VK_S,                'S'},
    {J_VK_T,                'T'},
    {J_VK_U,                'U'},
    {J_VK_V,                'V'},
    {J_VK_W,                'W'},
    {J_VK_X,                'X'},
    {J_VK_Y,                'Y'},
    {J_VK_Z,                'Z'},
    {J_VK_0,                '0'},
    {J_VK_1,                '1'},
    {J_VK_2,                '2'},
    {J_VK_3,                '3'},
    {J_VK_4,                '4'},
    {J_VK_5,                '5'},
    {J_VK_6,                '6'},
    {J_VK_7,                '7'},
    {J_VK_8,                '8'},
    {J_VK_9,                '9'},
    {J_VK_ENTER,            VK_RETURN},
    {J_VK_SPACE,            VK_SPACE},
    {J_VK_BACK_SPACE,       VK_BACK},
    {J_VK_TAB,              VK_TAB},
    {J_VK_ESCAPE,           VK_ESCAPE},
    {J_VK_INSERT,           VK_INSERT},
    {J_VK_DELETE,           VK_DELETE},
    {J_VK_HOME,             VK_HOME},
    {J_VK_END,              VK_END},
    {J_VK_PAGE_UP,          VK_PRIOR},
    {J_VK_PAGE_DOWN,        VK_NEXT},
    {J_VK_CLEAR,            VK_CLEAR}, // NumPad 5

    // NumPad with NumLock off & extended arrows block (triangular)
    {J_VK_LEFT,             VK_LEFT},
    {J_VK_RIGHT,            VK_RIGHT},
    {J_VK_UP,               VK_UP},
    {J_VK_DOWN,             VK_DOWN},

    // NumPad with NumLock on: numbers
    {J_VK_NUMPAD0,          VK_NUMPAD0},
    {J_VK_NUMPAD1,          VK_NUMPAD1},
    {J_VK_NUMPAD2,          VK_NUMPAD2},
    {J_VK_NUMPAD3,          VK_NUMPAD3},
    {J_VK_NUMPAD4,          VK_NUMPAD4},
    {J_VK_NUMPAD5,          VK_NUMPAD5},
    {J_VK_NUMPAD6,          VK_NUMPAD6},
    {J_VK_NUMPAD7,          VK_NUMPAD7},
    {J_VK_NUMPAD8,          VK_NUMPAD8},
    {J_VK_NUMPAD9,          VK_NUMPAD9},

    // NumPad with NumLock on
    {J_VK_MULTIPLY,         VK_MULTIPLY},
    {J_VK_ADD,              VK_ADD},
    {J_VK_SEPARATOR,        VK_SEPARATOR},
    {J_VK_SUBTRACT,         VK_SUBTRACT},
    {J_VK_DECIMAL,          VK_DECIMAL},
    {J_VK_DIVIDE,           VK_DIVIDE},

    // Functional keys
    {J_VK_F1,               VK_F1},
    {J_VK_F2,               VK_F2},
    {J_VK_F3,               VK_F3},
    {J_VK_F4,               VK_F4},
    {J_VK_F5,               VK_F5},
    {J_VK_F6,               VK_F6},
    {J_VK_F7,               VK_F7},
    {J_VK_F8,               VK_F8},
    {J_VK_F9,               VK_F9},
    {J_VK_F10,              VK_F10},
    {J_VK_F11,              VK_F11},
    {J_VK_F12,              VK_F12},
    {J_VK_F13,              VK_F13},
    {J_VK_F14,              VK_F14},
    {J_VK_F15,              VK_F15},
    {J_VK_F16,              VK_F16},
    {J_VK_F17,              VK_F17},
    {J_VK_F18,              VK_F18},
    {J_VK_F19,              VK_F19},
    {J_VK_F20,              VK_F20},
    {J_VK_F21,              VK_F21},
    {J_VK_F22,              VK_F22},
    {J_VK_F23,              VK_F23},
    {J_VK_F24,              VK_F24},

    {J_VK_PRINTSCREEN,      VK_SNAPSHOT},
    {J_VK_SCROLL_LOCK,      VK_SCROLL},
    {J_VK_PAUSE,            VK_PAUSE},
    {J_VK_CANCEL,           VK_CANCEL},
    {J_VK_HELP,             VK_HELP},

    // Japanese
/*
    {J_VK_CONVERT,          VK_CONVERT},
    {J_VK_NONCONVERT,       VK_NONCONVERT},
    {J_VK_INPUT_METHOD_ON_OFF, VK_KANJI},
    {J_VK_ALPHANUMERIC,     VK_DBE_ALPHANUMERIC},
    {J_VK_KATAKANA,         VK_DBE_KATAKANA},
    {J_VK_HIRAGANA,         VK_DBE_HIRAGANA},
    {J_VK_FULL_WIDTH,       VK_DBE_DBCSCHAR},
    {J_VK_HALF_WIDTH,       VK_DBE_SBCSCHAR},
    {J_VK_ROMAN_CHARACTERS, VK_DBE_ROMAN},
*/

    {J_VK_UNDEFINED,        0}
};

/*
Dynamic mapping table for OEM VK codes.  This table is refilled
by BuildDynamicKeyMapTable when keyboard layout is switched.
(see NT4 DDK src/input/inc/vkoem.h for OEM VK_ values).
*/
typedef struct {
    // OEM VK codes known in advance
    UINT windowsKey;
    // depends on input langauge (kbd layout)
    UINT javaKey;
} DynamicKeyMapEntry;

static DynamicKeyMapEntry dynamicKeyMapTable[] = {
    {0x00BA,  J_VK_UNDEFINED}, // VK_OEM_1
    {0x00BB,  J_VK_UNDEFINED}, // VK_OEM_PLUS
    {0x00BC,  J_VK_UNDEFINED}, // VK_OEM_COMMA
    {0x00BD,  J_VK_UNDEFINED}, // VK_OEM_MINUS
    {0x00BE,  J_VK_UNDEFINED}, // VK_OEM_PERIOD
    {0x00BF,  J_VK_UNDEFINED}, // VK_OEM_2
    {0x00C0,  J_VK_UNDEFINED}, // VK_OEM_3
    {0x00DB,  J_VK_UNDEFINED}, // VK_OEM_4
    {0x00DC,  J_VK_UNDEFINED}, // VK_OEM_5
    {0x00DD,  J_VK_UNDEFINED}, // VK_OEM_6
    {0x00DE,  J_VK_UNDEFINED}, // VK_OEM_7
    {0x00DF,  J_VK_UNDEFINED}, // VK_OEM_8
    {0x00E2,  J_VK_UNDEFINED}, // VK_OEM_102
    {0, 0}
};

// Auxiliary tables used to fill the above dynamic table.  We first
// find the character for the OEM VK code using ::MapVirtualKey and
// then go through these auxiliary tables to map it to Java VK code.

typedef struct {
    WCHAR c;
    UINT  javaKey;
} CharToVKEntry;

static const CharToVKEntry charToVKTable[] = {
    {L'!',   J_VK_EXCLAMATION_MARK},
    {L'"',   J_VK_QUOTEDBL},
    {L'#',   J_VK_NUMBER_SIGN},
    {L'$',   J_VK_DOLLAR},
    {L'&',   J_VK_AMPERSAND},
    {L'\'',  J_VK_QUOTE},
    {L'(',   J_VK_LEFT_PARENTHESIS},
    {L')',   J_VK_RIGHT_PARENTHESIS},
    {L'*',   J_VK_ASTERISK},
    {L'+',   J_VK_PLUS},
    {L',',   J_VK_COMMA},
    {L'-',   J_VK_MINUS},
    {L'.',   J_VK_PERIOD},
    {L'/',   J_VK_SLASH},
    {L':',   J_VK_COLON},
    {L';',   J_VK_SEMICOLON},
    {L'<',   J_VK_LESS},
    {L'=',   J_VK_EQUALS},
    {L'>',   J_VK_GREATER},
    {L'@',   J_VK_AT},
    {L'[',   J_VK_OPEN_BRACKET},
    {L'\\',  J_VK_BACK_SLASH},
    {L']',   J_VK_CLOSE_BRACKET},
    {L'^',   J_VK_CIRCUMFLEX},
    {L'_',   J_VK_UNDERSCORE},
    {L'`',   J_VK_BACK_QUOTE},
    {L'{',   J_VK_BRACELEFT},
    {L'}',   J_VK_BRACERIGHT},
    {0x00A1, J_VK_INVERTED_EXCLAMATION_MARK},
    {0x20A0, J_VK_EURO_SIGN}, // ????
    {0,0}
};

// For dead accents some layouts return ASCII punctuation, while some
// return spacing accent chars, so both should be listed.  NB: MS docs
// say that conversion routings return spacing accent character, not
// combining.
static const CharToVKEntry charToDeadVKTable[] = {
    {L'`',   J_VK_DEAD_GRAVE},
    {L'\'',  J_VK_DEAD_ACUTE},
    {0x00B4, J_VK_DEAD_ACUTE},
    {L'^',   J_VK_DEAD_CIRCUMFLEX},
    {L'~',   J_VK_DEAD_TILDE},
    {0x02DC, J_VK_DEAD_TILDE},
    {0x00AF, J_VK_DEAD_MACRON},
    {0x02D8, J_VK_DEAD_BREVE},
    {0x02D9, J_VK_DEAD_ABOVEDOT},
    {L'"',   J_VK_DEAD_DIAERESIS},
    {0x00A8, J_VK_DEAD_DIAERESIS},
    {0x02DA, J_VK_DEAD_ABOVERING},
    {0x02DD, J_VK_DEAD_DOUBLEACUTE},
    {0x02C7, J_VK_DEAD_CARON},            // aka hacek
    {L',',   J_VK_DEAD_CEDILLA},
    {0x00B8, J_VK_DEAD_CEDILLA},
    {0x02DB, J_VK_DEAD_OGONEK},
    {0x037A, J_VK_DEAD_IOTA},             // ASCII ???
    {0x309B, J_VK_DEAD_VOICED_SOUND},
    {0x309C, J_VK_DEAD_SEMIVOICED_SOUND},
    {0,0}
};

// ANSI CP identifiers are no longer than this
#define MAX_ACP_STR_LEN 7

static void BuildDynamicKeyMapTable()
{
    HKL hkl = GetKeyboardLayout(0);
    // Will need this to reset layout after dead keys.
    UINT spaceScanCode = MapVirtualKeyEx(VK_SPACE, 0, hkl);
    DynamicKeyMapEntry *dynamic;

    LANGID idLang = LOWORD(GetKeyboardLayout(0));
    UINT codePage;
    TCHAR strCodePage[MAX_ACP_STR_LEN];
    // use the LANGID to create a LCID
    LCID idLocale = MAKELCID(idLang, SORT_DEFAULT);
    // get the ANSI code page associated with this locale
    if (GetLocaleInfo(idLocale, LOCALE_IDEFAULTANSICODEPAGE,
    strCodePage, sizeof(strCodePage)/sizeof(TCHAR)) > 0 )
    {
        codePage = _ttoi(strCodePage);
    } else {
        codePage = GetACP();
    }

    // Entries in dynamic table that maps between Java VK and Windows
    // VK are built in three steps:
    //   1. Map windows VK to ANSI character (cannot map to unicode
    //      directly, since ::ToUnicode is not implemented on win9x)
    //   2. Convert ANSI char to Unicode char
    //   3. Map Unicode char to Java VK via two auxilary tables.

    for (dynamic = dynamicKeyMapTable; dynamic->windowsKey != 0; ++dynamic)
    {
        char cbuf[2] = { '\0', '\0'};
        WCHAR ucbuf[2] = { L'\0', L'\0' };
        int nchars;
        UINT scancode;
        const CharToVKEntry *charMap;
        int nconverted;
        WCHAR uc;
        BYTE kbdState[256];

        // Defaults to J_VK_UNDEFINED
        dynamic->javaKey = J_VK_UNDEFINED;

        GetKeyboardState(kbdState);

        kbdState[dynamic->windowsKey] |=  0x80; // Press the key.

        // Unpress modifiers, since they are most likely pressed as
        // part of the keyboard switching shortcut.
        kbdState[VK_CONTROL] &= ~0x80;
        kbdState[VK_SHIFT]   &= ~0x80;
        kbdState[VK_MENU]    &= ~0x80;

        scancode = MapVirtualKeyEx(dynamic->windowsKey, 0, hkl);
        nchars = ToAsciiEx(dynamic->windowsKey, scancode, kbdState,
                                 (WORD*)cbuf, 0, hkl);

        // Auxiliary table used to map Unicode character to Java VK.
        // Will assign a different table for dead keys (below).
        charMap = charToVKTable;

        if (nchars < 0) { // Dead key
            char junkbuf[2] = { '\0', '\0'};
            // Use a different table for dead chars since different layouts
            // return different characters for the same dead key.
            charMap = charToDeadVKTable;

            // We also need to reset layout so that next translation
            // is unaffected by the dead status.  We do this by
            // translating <SPACE> key.
            kbdState[dynamic->windowsKey] &= ~0x80;
            kbdState[VK_SPACE] |= 0x80;

            ToAsciiEx(VK_SPACE, spaceScanCode, kbdState,
                      (WORD*)junkbuf, 0, hkl);
        }

        nconverted = MultiByteToWideChar(codePage, 0,
                                         cbuf, 1, ucbuf, 2);

        uc = ucbuf[0];
        {
            const CharToVKEntry *map;
            for (map = charMap;  map->c != 0;  ++map) {
                if (uc == map->c) {
                    dynamic->javaKey = map->javaKey;
                    break;
                }
            }
        }

    } // for each VK_OEM_*
}

static jint GetModifiers(BOOL altKeyFlagged, UINT jkey) {
    jint modifiers = 0;
    // have to do &0xFFFF to avoid runtime assert caused by compiling with
    // /RTCcsu
    if ( HIBYTE((GetKeyState(VK_CONTROL) & 0xFFFF)) != 0 || J_VK_CONTROL == jkey ) {
        modifiers |= EVENT_CTRL_MASK;
    }
    if ( HIBYTE((GetKeyState(VK_SHIFT) & 0xFFFF)) != 0 || J_VK_SHIFT == jkey ) {
        modifiers |= EVENT_SHIFT_MASK;
    }
    if ( altKeyFlagged || HIBYTE((GetKeyState(VK_MENU) & 0xFFFF)) != 0 || J_VK_ALT == jkey ) {
        modifiers |= EVENT_ALT_MASK;
    }
    if ( HIBYTE((GetKeyState(VK_LBUTTON) & 0xFFFF)) != 0 ) {
        modifiers |= EVENT_BUTTON1_MASK;
    }
    if ( HIBYTE((GetKeyState(VK_MBUTTON) & 0xFFFF)) != 0 ) {
        modifiers |= EVENT_BUTTON2_MASK;
    }
    if ( HIBYTE((GetKeyState(VK_RBUTTON) & 0xFFFF)) != 0 ) {
        modifiers |= EVENT_BUTTON3_MASK;
    }

    return modifiers;
}

static BOOL IsAltKeyDown(BYTE flags, BOOL system) {
    // The Alt modifier is reported in the 29th bit of the lParam,
    // i.e., it is the 5th bit of `flags' (which is HIBYTE(HIWORD(lParam))).
    return system && ( flags & (1<<5) ) != 0;
}

UINT WindowsKeyToJavaKey(UINT windowsKey)
{
    int i, j, javaKey = J_VK_UNDEFINED;
    // for the general case, use a bi-directional table
    for (i = 0; keyMapTable[i].windowsKey != 0; i++) {
        if (keyMapTable[i].windowsKey == windowsKey) {
            javaKey = keyMapTable[i].javaKey;
        }
    }
    if( J_VK_UNDEFINED == javaKey ) {
        for (j = 0; dynamicKeyMapTable[j].windowsKey != 0; j++) {
            if (dynamicKeyMapTable[j].windowsKey == windowsKey) {
                if (dynamicKeyMapTable[j].javaKey != J_VK_UNDEFINED) {
                    javaKey = dynamicKeyMapTable[j].javaKey;
                } else {
                    break;
                }
            }
        }
    }

#ifdef DEBUG_KEYS
    STD_PRINT("*** WindowsWindow: WindowsKeyToJavaKey 0x%X -> 0x%X\n", windowsKey, javaKey);
#endif
    return javaKey;
}

#ifndef MAPVK_VSC_TO_VK
    #define MAPVK_VSC_TO_VK 1
#endif
#ifndef MAPVK_VK_TO_CHAR
    #define MAPVK_VK_TO_CHAR 2
#endif

static UINT WmVKey2ShiftedChar(UINT wkey, UINT modifiers) {
    UINT c = MapVirtualKey(wkey, MAPVK_VK_TO_CHAR);
    if( 0 != ( modifiers & EVENT_SHIFT_MASK ) ) {
        return islower(c) ? toupper(c) : c;
    }
    return isupper(c) ? tolower(c) : c;
}

static int WmChar(JNIEnv *env, jobject window, UINT character, WORD repCnt, BYTE scanCode, BYTE flags, BOOL system) {
    UINT modifiers = 0, jkey = 0, wkey = 0;

    wkey = MapVirtualKey(scanCode, MAPVK_VSC_TO_VK);
    jkey = WindowsKeyToJavaKey(wkey);
    modifiers = GetModifiers( IsAltKeyDown(flags, system), 0 );

    if (character == VK_RETURN) {
        character = J_VK_ENTER;
    }

    (*env)->CallVoidMethod(env, window, sendKeyEventID,
                           (jint) EVENT_KEY_TYPED,
                           modifiers,
                           (jint) jkey,
                           (jchar) character);
    return 1;
}

static int WmKeyDown(JNIEnv *env, jobject window, UINT wkey, WORD repCnt, BYTE scanCode, BYTE flags, BOOL system) {
    UINT modifiers = 0, jkey = 0, character = 0;
    if (wkey == VK_PROCESSKEY) {
        return 1;
    }

    jkey = WindowsKeyToJavaKey(wkey);
    modifiers = GetModifiers( IsAltKeyDown(flags, system), jkey );
    character = WmVKey2ShiftedChar(wkey, modifiers);

/*
    character = WindowsKeyToJavaChar(wkey, modifiers, SAVE);
*/

    (*env)->CallVoidMethod(env, window, sendKeyEventID,
                           (jint) EVENT_KEY_PRESSED,
                           modifiers,
                           (jint) jkey,
                           (jchar) character);

    /* windows does not create a WM_CHAR for the Del key
       for some reason, so we need to create the KEY_TYPED event on the
       WM_KEYDOWN.
     */
    if (jkey == J_VK_DELETE) {
        (*env)->CallVoidMethod(env, window, sendKeyEventID,
                               (jint) EVENT_KEY_TYPED,
                               modifiers,
                               (jint) 0,
                               (jchar) '\177');
    }

    return 0;
}

static int WmKeyUp(JNIEnv *env, jobject window, UINT wkey, WORD repCnt, BYTE scanCode, BYTE flags, BOOL system) {
    UINT modifiers = 0, jkey = 0, character = 0;
    if (wkey == VK_PROCESSKEY) {
        return 1;
    }

    jkey = WindowsKeyToJavaKey(wkey);
    modifiers = GetModifiers( IsAltKeyDown(flags, system), jkey );
    character = WmVKey2ShiftedChar(wkey, modifiers);

/*
    character = WindowsKeyToJavaChar(wkey, modifiers, SAVE);
*/

    (*env)->CallVoidMethod(env, window, sendKeyEventID,
                           (jint) EVENT_KEY_RELEASED,
                           modifiers,
                           (jint) jkey,
                           (jchar) character);

    return 0;
}

static void NewtWindows_requestFocus (JNIEnv *env, jobject window, HWND hwnd, jboolean force) {
    HWND pHwnd, current;
    BOOL isEnabled = IsWindowEnabled(hwnd);
    pHwnd = GetParent(hwnd);
    current = GetFocus();
    DBG_PRINT("*** WindowsWindow: requestFocus.S force %d, parent %p, window %p, isEnabled %d, isCurrent %d\n", 
        (int)force, (void*)pHwnd, (void*)hwnd, isEnabled, current==hwnd);

    if( JNI_TRUE==force || current!=hwnd || !isEnabled ) {
        UINT flags = SWP_SHOWWINDOW | SWP_NOSIZE | SWP_NOMOVE;
        if(!isEnabled) {
            EnableWindow(hwnd, TRUE);
        }
        SetWindowPos(hwnd, HWND_TOP, 0, 0, 0, 0, flags);
        SetForegroundWindow(hwnd);  // Slightly Higher Priority
        SetFocus(hwnd);// Sets Keyboard Focus To Window (activates parent window if exist, or this window)
        if(NULL!=pHwnd) {
            SetActiveWindow(hwnd);
        }
        current = GetFocus();
        DBG_PRINT("*** WindowsWindow: requestFocus.X1 isCurrent %d\n", current==hwnd);
    }
    DBG_PRINT("*** WindowsWindow: requestFocus.XX\n");
}

static void NewtWindows_trackPointerLeave(HWND hwnd) {
    TRACKMOUSEEVENT tme;
    memset(&tme, 0, sizeof(TRACKMOUSEEVENT));
    tme.cbSize = sizeof(TRACKMOUSEEVENT);
    tme.dwFlags = TME_LEAVE;
    tme.hwndTrack = hwnd;
    tme.dwHoverTime = 0; // we don't use TME_HOVER
    TrackMouseEvent(&tme);
}

#if 0

static RECT* UpdateInsets(JNIEnv *env, jobject window, HWND hwnd)
{
    // being naughty here
    static RECT m_insets = { 0, 0, 0, 0 };
    RECT outside;
    RECT inside;
    POINT *rp_inside = (POINT *) (void *) &inside;
    int dx, dy, dw, dh;

    if (IsIconic(hwnd)) {
        m_insets.left = m_insets.top = m_insets.right = m_insets.bottom = -1;
        return FALSE;
    }

    m_insets.left = m_insets.top = m_insets.right = m_insets.bottom = 0;

    GetClientRect(hwnd, &inside);
    GetWindowRect(hwnd, &outside);

    DBG_PRINT("*** WindowsWindow: UpdateInsets (a1) window %p, Inside CC: %d/%d - %d/%d %dx%d\n", 
        (void*)hwnd,
        (int)inside.left, (int)inside.top, (int)inside.right, (int)inside.bottom, 
        (int)(inside.right - inside.left), (int)(inside.bottom - inside.top));
    DBG_PRINT("*** WindowsWindow: UpdateInsets (a1) window %p, Outside SC: %d/%d - %d/%d %dx%d\n", 
        (void*)hwnd,
        (int)outside.left, (int)outside.top, (int)outside.right, (int)outside.bottom, 
        (int)(outside.right - outside.left), (int)(outside.bottom - outside.top));

    // xform client -> screen coord
    ClientToScreen(hwnd, rp_inside);
    ClientToScreen(hwnd, rp_inside+1);

    DBG_PRINT("*** WindowsWindow: UpdateInsets (a2) window %p, Inside SC: %d/%d - %d/%d %dx%d\n", 
        (void*)hwnd,
        (int)inside.left, (int)inside.top, (int)inside.right, (int)inside.bottom, 
        (int)(inside.right - inside.left), (int)(inside.bottom - inside.top));

    m_insets.top = inside.top - outside.top;
    m_insets.bottom = outside.bottom - inside.bottom;
    m_insets.left = inside.left - outside.left;
    m_insets.right = outside.right - inside.right;

    DBG_PRINT("*** WindowsWindow: UpdateInsets (1.0) window %p, %d/%d - %d/%d %dx%d\n", 
        (void*)hwnd, 
        (int)m_insets.left, (int)m_insets.top, (int)m_insets.right, (int)m_insets.bottom,
        (int)(m_insets.right-m_insets.left), (int)(m_insets.top-m_insets.bottom));

    (*env)->CallVoidMethod(env, window, insetsChangedID, JNI_FALSE,
                           m_insets.left, m_insets.right,
                           m_insets.top, m_insets.bottom);
    return &m_insets;
}

#else

static RECT* UpdateInsets(JNIEnv *env, jobject window, HWND hwnd)
{
    // being naughty here
    static RECT m_insets = { 0, 0, 0, 0 };
    RECT outside;
    RECT inside;

    if (IsIconic(hwnd)) {
        m_insets.left = m_insets.top = m_insets.right = m_insets.bottom = -1;
        return FALSE;
    }

    m_insets.left = m_insets.top = m_insets.right = m_insets.bottom = 0;

    GetClientRect(hwnd, &inside);
    GetWindowRect(hwnd, &outside);

    if (outside.right - outside.left > 0 && outside.bottom - outside.top > 0) {
        MapWindowPoints(hwnd, 0, (LPPOINT)&inside, 2);
        m_insets.top = inside.top - outside.top;
        m_insets.bottom = outside.bottom - inside.bottom;
        m_insets.left = inside.left - outside.left;
        m_insets.right = outside.right - inside.right;
    } else {
        m_insets.top = -1;
    }
    if (m_insets.left < 0 || m_insets.top < 0 ||
        m_insets.right < 0 || m_insets.bottom < 0)
    {
        LONG style = GetWindowLong(hwnd, GWL_STYLE);

        BOOL bIsUndecorated = (style & (WS_CHILD|WS_POPUP)) != 0;
        if (!bIsUndecorated) {
            /* Get outer frame sizes. */
            if (style & WS_THICKFRAME) {
                m_insets.left = m_insets.right =
                    GetSystemMetrics(SM_CXSIZEFRAME);
                m_insets.top = m_insets.bottom =
                    GetSystemMetrics(SM_CYSIZEFRAME);
            } else {
                m_insets.left = m_insets.right =
                    GetSystemMetrics(SM_CXDLGFRAME);
                m_insets.top = m_insets.bottom =
                    GetSystemMetrics(SM_CYDLGFRAME);
            }

            /* Add in title. */
            m_insets.top += GetSystemMetrics(SM_CYCAPTION);
        } else {
            /* undo the -1 set above */
            m_insets.left = m_insets.top = m_insets.right = m_insets.bottom = 0;
        }
    }

    DBG_PRINT("*** WindowsWindow: UpdateInsets window %p, [l %d, r %d - t %d, b %d - %dx%d]\n", 
        (void*)hwnd, (int)m_insets.left, (int)m_insets.right, (int)m_insets.top, (int)m_insets.bottom,
        (int) ( m_insets.left + m_insets.right ), (int) (m_insets.top + m_insets.bottom));

    (*env)->CallVoidMethod(env, window, insetsChangedID, JNI_FALSE,
                           (int)m_insets.left, (int)m_insets.right, (int)m_insets.top, (int)m_insets.bottom);
    return &m_insets;
}

#endif

static void WmSize(JNIEnv *env, jobject window, HWND wnd, UINT type)
{
    RECT rc;
    int w, h;
    BOOL isVisible = IsWindowVisible(wnd);

    if (type == SIZE_MINIMIZED) {
        // TODO: deal with minimized window sizing
        return;
    }

    // make sure insets are up to date
    (void)UpdateInsets(env, window, wnd);

    GetClientRect(wnd, &rc);
    
    // we report back the dimensions of the client area
    w = (int) ( rc.right  - rc.left );
    h = (int) ( rc.bottom - rc.top );

    DBG_PRINT("*** WindowsWindow: WmSize window %p, %dx%d, visible %d\n", (void*)wnd, w, h, isVisible);

    (*env)->CallVoidMethod(env, window, sizeChangedID, JNI_FALSE, w, h, JNI_FALSE);
}

#ifdef TEST_MOUSE_HOOKS

static HHOOK hookLLMP;
static HHOOK hookMP;

static LRESULT CALLBACK HookLowLevelMouseProc (int code, WPARAM wParam, LPARAM lParam)
{
    // if (code == HC_ACTION)
    {
        const char *msg;
        char msg_buff[128];
        switch (wParam)
        {
            case WM_LBUTTONDOWN: msg = "WM_LBUTTONDOWN"; break;
            case WM_LBUTTONUP: msg = "WM_LBUTTONUP"; break;
            case WM_MOUSEMOVE: msg = "WM_MOUSEMOVE"; break;
            case WM_MOUSEWHEEL: msg = "WM_MOUSEWHEEL"; break;
            case WM_MOUSEHWHEEL: msg = "WM_MOUSEHWHEEL"; break;
            case WM_RBUTTONDOWN: msg = "WM_RBUTTONDOWN"; break;
            case WM_RBUTTONUP: msg = "WM_RBUTTONUP"; break;
            default: 
                sprintf(msg_buff, "Unknown msg: %u", wParam); 
                msg = msg_buff;
                break;
        }//switch

        const MSLLHOOKSTRUCT *p = (MSLLHOOKSTRUCT*)lParam;
        DBG_PRINT("**** LLMP: Code: 0x%X: %s - %d/%d\n", code, msg, (int)p->pt.x, (int)p->pt.y);
    //} else {
    //    DBG_PRINT("**** LLMP: CODE: 0x%X\n", code);
    }
    return CallNextHookEx(hookLLMP, code, wParam, lParam); 
}

static LRESULT CALLBACK HookMouseProc (int code, WPARAM wParam, LPARAM lParam)
{
    // if (code == HC_ACTION)
    {
        const char *msg;
        char msg_buff[128];
        switch (wParam)
        {
            case WM_LBUTTONDOWN: msg = "WM_LBUTTONDOWN"; break;
            case WM_LBUTTONUP: msg = "WM_LBUTTONUP"; break;
            case WM_MOUSEMOVE: msg = "WM_MOUSEMOVE"; break;
            case WM_MOUSEWHEEL: msg = "WM_MOUSEWHEEL"; break;
            case WM_MOUSEHWHEEL: msg = "WM_MOUSEHWHEEL"; break;
            case WM_RBUTTONDOWN: msg = "WM_RBUTTONDOWN"; break;
            case WM_RBUTTONUP: msg = "WM_RBUTTONUP"; break;
            default: 
                sprintf(msg_buff, "Unknown msg: %u", wParam); 
                msg = msg_buff;
                break;
        }//switch

        const MOUSEHOOKSTRUCT *p = (MOUSEHOOKSTRUCT*)lParam;
        DBG_PRINT("**** MP: Code: 0x%X: %s - hwnd %p, %d/%d\n", code, msg, p->hwnd, (int)p->pt.x, (int)p->pt.y);
    //} else {
    //    DBG_PRINT("**** MP: CODE: 0x%X\n", code);
    }
    return CallNextHookEx(hookMP, code, wParam, lParam); 
}

#endif


static LRESULT CALLBACK wndProc(HWND wnd, UINT message, WPARAM wParam, LPARAM lParam) {
    LRESULT res = 0;
    int useDefWindowProc = 0;
    JNIEnv *env = NULL;
    jobject window = NULL;
    BOOL isKeyDown = FALSE;
    WindowUserData * wud;
    WORD repCnt; 
    BYTE scanCode, flags;

#if !defined(__MINGW64__) && ( defined(UNDER_CE) || _MSC_VER <= 1200 )
    wud = (WindowUserData *) GetWindowLong(wnd, GWL_USERDATA);
#else
    wud = (WindowUserData *) GetWindowLongPtr(wnd, GWLP_USERDATA);
#endif
    if(NULL==wud) {
        return DefWindowProc(wnd, message, wParam, lParam);
    }
    env = wud->jenv;
    window = wud->jinstance;

    // DBG_PRINT("*** WindowsWindow: thread 0x%X - window %p -> %p, msg 0x%X, %d/%d\n", (int)GetCurrentThreadId(), wnd, window, message, (int)LOWORD(lParam), (int)HIWORD(lParam));

    if (NULL==window || NULL==env) {
        return DefWindowProc(wnd, message, wParam, lParam);
    }

    switch (message) {

    //
    // The signal pipeline for destruction is:
    //    Java::DestroyWindow(wnd) _or_ window-close-button -> 
    //     WM_CLOSE -> Java::windowDestroyNotify -> W_DESTROY
    case WM_CLOSE:
        (*env)->CallBooleanMethod(env, window, windowDestroyNotifyID, JNI_FALSE);
        break;

    case WM_DESTROY:
        {
#if !defined(__MINGW64__) && ( defined(UNDER_CE) || _MSC_VER <= 1200 )
            SetWindowLong(wnd, GWL_USERDATA, (intptr_t) NULL);
#else
            SetWindowLongPtr(wnd, GWLP_USERDATA, (intptr_t) NULL);
#endif
            free(wud); wud=NULL;
            (*env)->DeleteGlobalRef(env, window);
        }
        break;

    case WM_SYSCHAR:
        repCnt = HIWORD(lParam); scanCode = LOBYTE(repCnt); flags = HIBYTE(repCnt);
        repCnt = LOWORD(lParam);
#ifdef DEBUG_KEYS
        STD_PRINT("*** WindowsWindow: windProc WM_SYSCHAR sending window %p -> %p, char 0x%X, repCnt %d, scanCode 0x%X, flags 0x%X\n", wnd, window, (int)wParam, (int)repCnt, (int)scanCode, (int)flags);
#endif
        useDefWindowProc = WmChar(env, window, wParam, repCnt, scanCode, flags, TRUE);
        break;

    case WM_SYSKEYDOWN:
        repCnt = HIWORD(lParam); scanCode = LOBYTE(repCnt); flags = HIBYTE(repCnt);
        repCnt = LOWORD(lParam);
#ifdef DEBUG_KEYS
        STD_PRINT("*** WindowsWindow: windProc WM_SYSKEYDOWN sending window %p -> %p, code 0x%X, repCnt %d, scanCode 0x%X, flags 0x%X\n", wnd, window, (int)wParam, (int)repCnt, (int)scanCode, (int)flags);
#endif
        useDefWindowProc = WmKeyDown(env, window, wParam, repCnt, scanCode, flags, TRUE);
        break;

    case WM_SYSKEYUP:
        repCnt = HIWORD(lParam); scanCode = LOBYTE(repCnt); flags = HIBYTE(repCnt);
        repCnt = LOWORD(lParam);
#ifdef DEBUG_KEYS
        STD_PRINT("*** WindowsWindow: windProc WM_SYSKEYUP sending window %p -> %p, code 0x%X, repCnt %d, scanCode 0x%X, flags 0x%X\n", wnd, window, (int)wParam, (int)repCnt, (int)scanCode, (int)flags);
#endif
        useDefWindowProc = WmKeyUp(env, window, wParam, repCnt, scanCode, flags, TRUE);
        break;

    case WM_CHAR:
        repCnt = HIWORD(lParam); scanCode = LOBYTE(repCnt); flags = HIBYTE(repCnt);
        repCnt = LOWORD(lParam);
#ifdef DEBUG_KEYS
        STD_PRINT("*** WindowsWindow: windProc WM_CHAR sending window %p -> %p, char 0x%X, repCnt %d, scanCode 0x%X, flags 0x%X\n", wnd, window, (int)wParam, (int)repCnt, (int)scanCode, (int)flags);
#endif
        useDefWindowProc = WmChar(env, window, wParam, repCnt, scanCode, flags, FALSE);
        break;
        
    case WM_KEYDOWN:
        repCnt = HIWORD(lParam); scanCode = LOBYTE(repCnt); flags = HIBYTE(repCnt);
        repCnt = LOWORD(lParam);
#ifdef DEBUG_KEYS
        STD_PRINT("*** WindowsWindow: windProc WM_KEYDOWN sending window %p -> %p, code 0x%X, repCnt %d, scanCode 0x%X, flags 0x%X\n", wnd, window, (int)wParam, (int)repCnt, (int)scanCode, (int)flags);
#endif
        useDefWindowProc = WmKeyDown(env, window, wParam, repCnt, scanCode, flags, FALSE);
        break;

    case WM_KEYUP:
        repCnt = HIWORD(lParam); scanCode = LOBYTE(repCnt); flags = HIBYTE(repCnt);
        repCnt = LOWORD(lParam);
#ifdef DEBUG_KEYS
        STD_PRINT("*** WindowsWindow: windProc WM_KEYUP sending window %p -> %p, code 0x%X, repCnt %d, scanCode 0x%X, flags 0x%X\n", wnd, window, (int)wParam, (int)repCnt, (int)scanCode, (int)flags);
#endif
        useDefWindowProc = WmKeyUp(env, window, wParam, repCnt, scanCode, flags, FALSE);
        break;

    case WM_SIZE:
        WmSize(env, window, wnd, (UINT)wParam);
        break;

    case WM_SETTINGCHANGE:
        if (wParam == SPI_SETNONCLIENTMETRICS) {
            // make sure insets are updated, we don't need to resize the window 
            // because the size of the client area doesn't change
            (void)UpdateInsets(env, window, wnd);
        } else {
            useDefWindowProc = 1;
        }
        break;


    case WM_LBUTTONDOWN:
        DBG_PRINT("*** WindowsWindow: LBUTTONDOWN\n");
        (*env)->CallVoidMethod(env, window, requestFocusID, JNI_FALSE);
        (*env)->CallVoidMethod(env, window, sendMouseEventID,
                               (jint) EVENT_MOUSE_PRESSED,
                               GetModifiers( FALSE, 0 ),
                               (jint) GET_X_LPARAM(lParam), (jint) GET_Y_LPARAM(lParam),
                               (jint) 1, (jfloat) 0.0f);
        useDefWindowProc = 1;
        break;

    case WM_LBUTTONUP:
        (*env)->CallVoidMethod(env, window, sendMouseEventID,
                               (jint) EVENT_MOUSE_RELEASED,
                               GetModifiers( FALSE, 0 ),
                               (jint) GET_X_LPARAM(lParam), (jint) GET_Y_LPARAM(lParam),
                               (jint) 1, (jfloat) 0.0f);
        useDefWindowProc = 1;
        break;

    case WM_MBUTTONDOWN:
        DBG_PRINT("*** WindowsWindow: MBUTTONDOWN\n");
        (*env)->CallVoidMethod(env, window, requestFocusID, JNI_FALSE);
        (*env)->CallVoidMethod(env, window, sendMouseEventID,
                               (jint) EVENT_MOUSE_PRESSED,
                               GetModifiers( FALSE, 0 ),
                               (jint) GET_X_LPARAM(lParam), (jint) GET_Y_LPARAM(lParam),
                               (jint) 2, (jfloat) 0.0f);
        useDefWindowProc = 1;
        break;

    case WM_MBUTTONUP:
        (*env)->CallVoidMethod(env, window, sendMouseEventID,
                               (jint) EVENT_MOUSE_RELEASED,
                               GetModifiers( FALSE, 0 ),
                               (jint) GET_X_LPARAM(lParam), (jint) GET_Y_LPARAM(lParam),
                               (jint) 2, (jfloat) 0.0f);
        useDefWindowProc = 1;
        break;

    case WM_RBUTTONDOWN:
        DBG_PRINT("*** WindowsWindow: RBUTTONDOWN\n");
        (*env)->CallVoidMethod(env, window, requestFocusID, JNI_FALSE);
        (*env)->CallVoidMethod(env, window, sendMouseEventID,
                               (jint) EVENT_MOUSE_PRESSED,
                               GetModifiers( FALSE, 0 ),
                               (jint) GET_X_LPARAM(lParam), (jint) GET_Y_LPARAM(lParam),
                               (jint) 3, (jfloat) 0.0f);
        useDefWindowProc = 1;
        break;

    case WM_RBUTTONUP:
        (*env)->CallVoidMethod(env, window, sendMouseEventID,
                               (jint) EVENT_MOUSE_RELEASED,
                               GetModifiers( FALSE, 0 ),
                               (jint) GET_X_LPARAM(lParam), (jint) GET_Y_LPARAM(lParam),
                               (jint) 3,  (jfloat) 0.0f);
        useDefWindowProc = 1;
        break;

    case WM_MOUSEMOVE:
        (*env)->CallVoidMethod(env, window, sendMouseEventID,
                               (jint) EVENT_MOUSE_MOVED,
                               GetModifiers( FALSE, 0 ),
                               (jint) GET_X_LPARAM(lParam), (jint) GET_Y_LPARAM(lParam),
                               (jint) 0,  (jfloat) 0.0f);
        useDefWindowProc = 1;
        break;
    case WM_MOUSELEAVE:
        (*env)->CallVoidMethod(env, window, enqueueMouseEventID, JNI_FALSE,
                               (jint) EVENT_MOUSE_EXITED,
                               0,
                               (jint) -1, (jint) -1, // fake
                               (jint) 0,  (jfloat) 0.0f);
        useDefWindowProc = 1;
        break;
    // Java synthesizes EVENT_MOUSE_ENTERED

    case WM_HSCROLL: { // Only delivered if windows has WS_HSCROLL, hence dead code!
            int sb = LOWORD(wParam);
            int modifiers = GetModifiers( FALSE, 0 ) | EVENT_SHIFT_MASK;
            float rotation;
            switch(sb) {
                case SB_LINELEFT:
                    rotation = 1.0f;
                    break;
                case SB_PAGELEFT:
                    rotation = 2.0f;
                    break;
                case SB_LINERIGHT:
                    rotation = -1.0f;
                    break;
                case SB_PAGERIGHT:
                    rotation = -1.0f;
                    break;
            }
            DBG_PRINT("*** WindowsWindow: WM_HSCROLL 0x%X, rotation %f, mods 0x%X\n", sb, rotation, modifiers);
            (*env)->CallVoidMethod(env, window, sendMouseEventID,
                                   (jint) EVENT_MOUSE_WHEEL_MOVED,
                                   modifiers,
                                   (jint) 0, (jint) 0,
                                   (jint) 1,  (jfloat) rotation);
            useDefWindowProc = 1;
            break;
        }
    case WM_MOUSEHWHEEL: /* tilt */
    case WM_MOUSEWHEEL: /* rotation */ {
        // need to convert the coordinates to component-relative
        int x = GET_X_LPARAM(lParam);
        int y = GET_Y_LPARAM(lParam);
        int modifiers = GetModifiers( FALSE, 0 );
        float rotationOrTilt = (float)(GET_WHEEL_DELTA_WPARAM(wParam))/WHEEL_DELTAf;
        int vKeys = GET_KEYSTATE_WPARAM(wParam);
        POINT eventPt;
        eventPt.x = x;
        eventPt.y = y;
        ScreenToClient(wnd, &eventPt);

        if( WM_MOUSEHWHEEL == message ) {
            modifiers |= EVENT_SHIFT_MASK;
            DBG_PRINT("*** WindowsWindow: WM_MOUSEHWHEEL %d/%d, tilt %f, vKeys 0x%X, mods 0x%X\n", 
                (int)eventPt.x, (int)eventPt.y, rotationOrTilt, vKeys, modifiers);
        } else {
            DBG_PRINT("*** WindowsWindow: WM_MOUSEWHEEL %d/%d, rotation %f, vKeys 0x%X, mods 0x%X\n", 
                (int)eventPt.x, (int)eventPt.y, rotationOrTilt, vKeys, modifiers);
        }
        (*env)->CallVoidMethod(env, window, sendMouseEventID,
                               (jint) EVENT_MOUSE_WHEEL_MOVED,
                               modifiers,
                               (jint) eventPt.x, (jint) eventPt.y,
                               (jint) 1,  (jfloat) rotationOrTilt);
        useDefWindowProc = 1;
        break;
    }

    case WM_SETFOCUS:
        DBG_PRINT("*** WindowsWindow: WM_SETFOCUS window %p, lost %p\n", wnd, (HWND)wParam);
        (*env)->CallVoidMethod(env, window, focusChangedID, JNI_FALSE, JNI_TRUE);
        useDefWindowProc = 1;
        break;

    case WM_KILLFOCUS:
        DBG_PRINT("*** WindowsWindow: WM_KILLFOCUS window %p, received %p\n", wnd, (HWND)wParam);
        (*env)->CallVoidMethod(env, window, focusChangedID, JNI_FALSE, JNI_FALSE);
        useDefWindowProc = 1;
        break;

    case WM_SHOWWINDOW:
        (*env)->CallVoidMethod(env, window, visibleChangedID, JNI_FALSE, wParam==TRUE?JNI_TRUE:JNI_FALSE);
        break;

    case WM_MOVE:
        DBG_PRINT("*** WindowsWindow: WM_MOVE window %p, %d/%d\n", wnd, GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam));
        (*env)->CallVoidMethod(env, window, positionChangedID, JNI_FALSE, (jint)GET_X_LPARAM(lParam), (jint)GET_Y_LPARAM(lParam));
        useDefWindowProc = 1;
        break;

    case WM_PAINT: {
        RECT r;
        useDefWindowProc = 0;
        if (GetUpdateRect(wnd, &r, FALSE /* do not erase background */)) {
            // clear the whole client area and issue repaint for it, w/o looping through erase background
            ValidateRect(wnd, NULL); // clear all!
            (*env)->CallVoidMethod(env, window, windowRepaintID, JNI_FALSE, 0, 0, -1, -1);
        } else {
            // shall not happen ?
            ValidateRect(wnd, NULL); // clear all!
        }
        // return 0 == done
        break;
    }
    case WM_ERASEBKGND:
        // ignore erase background
        (*env)->CallVoidMethod(env, window, windowRepaintID, JNI_FALSE, 0, 0, -1, -1);
        useDefWindowProc = 0;
        res = 1; // return 1 == done, OpenGL, etc .. erases the background, hence we claim to have just done this
        break;


    default:
        useDefWindowProc = 1;
    }

    if (useDefWindowProc) {
        return DefWindowProc(wnd, message, wParam, lParam);
    }
    return res;
}

/*
 * Class:     jogamp_newt_driver_windows_DisplayDriver
 * Method:    DispatchMessages
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_jogamp_newt_driver_windows_DisplayDriver_DispatchMessages0
  (JNIEnv *env, jclass clazz)
{
    int i = 0;
    MSG msg;
    BOOL gotOne;

    // Periodically take a break
    do {
        gotOne = PeekMessage(&msg, (HWND) NULL, 0, 0, PM_REMOVE);
        // DBG_PRINT("*** WindowsWindow.DispatchMessages0: thread 0x%X - gotOne %d\n", (int)GetCurrentThreadId(), (int)gotOne);
        if (gotOne) {
            ++i;
            TranslateMessage(&msg);
            DispatchMessage(&msg);
        }
    } while (gotOne && i < 100);
}

/*
 * Class:     jogamp_newt_driver_windows_ScreenDriver
 * Method:    getOriginX0
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_jogamp_newt_driver_windows_ScreenDriver_getOriginX0
  (JNIEnv *env, jobject obj, jint scrn_idx)
{
    if( GetSystemMetrics( SM_CMONITORS) > 1) {
        return (jint)GetSystemMetrics(SM_XVIRTUALSCREEN);
    } else {
        return 0;
    }
}

/*
 * Class:     jogamp_newt_driver_windows_ScreenDriver
 * Method:    getOriginY0
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_jogamp_newt_driver_windows_ScreenDriver_getOriginY0
  (JNIEnv *env, jobject obj, jint scrn_idx)
{
    if( GetSystemMetrics( SM_CMONITORS ) > 1) {
        return (jint)GetSystemMetrics(SM_YVIRTUALSCREEN);
    } else {
        return 0;
    }
}

/*
 * Class:     jogamp_newt_driver_windows_ScreenDriver
 * Method:    getWidthImpl
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_jogamp_newt_driver_windows_ScreenDriver_getWidthImpl0
  (JNIEnv *env, jobject obj, jint scrn_idx)
{
    if( GetSystemMetrics( SM_CMONITORS) > 1) {
        return (jint)GetSystemMetrics(SM_CXVIRTUALSCREEN);
    } else {
        return (jint)GetSystemMetrics(SM_CXSCREEN);
    }
}

/*
 * Class:     jogamp_newt_driver_windows_ScreenDriver
 * Method:    getHeightImpl
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_jogamp_newt_driver_windows_ScreenDriver_getHeightImpl0
  (JNIEnv *env, jobject obj, jint scrn_idx)
{
    if( GetSystemMetrics( SM_CMONITORS ) > 1) {
        return (jint)GetSystemMetrics(SM_CYVIRTUALSCREEN);
    } else {
        return (jint)GetSystemMetrics(SM_CYSCREEN);
    }
}

static int NewtScreen_RotationNativeCCW2NewtCCW(JNIEnv *env, int native) {
    int newt;
    switch (native) {
        case DMDO_DEFAULT:
            newt = 0;
            break;
        case DMDO_90:
            newt = 90;
            break;
        case DMDO_180:
            newt = 180;
            break;
        case DMDO_270:
            newt = 270;
            break;
        default:
            NewtCommon_throwNewRuntimeException(env, "invalid native rotation: %d", native);
        break;
    }
    return newt;
}

static int NewtScreen_RotationNewtCCW2NativeCCW(JNIEnv *env, jint newt) {
    int native;
    switch (newt) {
        case 0:
            native = DMDO_DEFAULT;
            break;
        case 90:
            native = DMDO_90;
            break;
        case 180:
            native = DMDO_180;
            break;
        case 270:
            native = DMDO_270;
            break;
        default:
            NewtCommon_throwNewRuntimeException(env, "invalid newt rotation: %d", newt);
    }
    return native;
}

/*
static void NewtScreen_scanDisplayDevices() {
    DISPLAY_DEVICE device;
    int i = 0;
    LPCTSTR name;
    while(NULL != (name = NewtScreen_getDisplayDeviceName(&device, i))) {
        DBG_PRINT("*** [%d]: <%s> active %d\n", i, name, ( 0 != ( device.StateFlags & DISPLAY_DEVICE_ACTIVE ) ) );
        i++;
    }
}*/

static LPCTSTR NewtScreen_getDisplayDeviceName(DISPLAY_DEVICE * device, int scrn_idx) {
    device->cb = sizeof(DISPLAY_DEVICE);
    if( FALSE == EnumDisplayDevices(NULL, scrn_idx, device, 0) ) {
        DBG_PRINT("*** WindowsWindow: getDisplayDeviceName.EnumDisplayDevices(scrn_idx %d) -> FALSE\n", scrn_idx);
        return NULL;
    }

    if( 0 == ( device->StateFlags & DISPLAY_DEVICE_ACTIVE ) ) {
        DBG_PRINT("*** WindowsWindow: !DISPLAY_DEVICE_ACTIVE(scrn_idx %d)\n", scrn_idx);
        return NULL;
    }

    return device->DeviceName;
}

static HDC NewtScreen_createDisplayDC(LPCTSTR displayDeviceName) {
    return CreateDC("DISPLAY", displayDeviceName, NULL, NULL);
}

/*
 * Class:     jogamp_newt_driver_windows_ScreenDriver
 * Method:    getScreenMode0
 * Signature: (II)[I
 */
JNIEXPORT jintArray JNICALL Java_jogamp_newt_driver_windows_ScreenDriver_getScreenMode0
  (JNIEnv *env, jobject obj, jint scrn_idx, jint mode_idx)
{
    DISPLAY_DEVICE device;
    int prop_num = NUM_SCREEN_MODE_PROPERTIES_ALL;
    LPCTSTR deviceName = NewtScreen_getDisplayDeviceName(&device, scrn_idx);
    if(NULL == deviceName) {
        DBG_PRINT("*** WindowsWindow: getScreenMode.getDisplayDeviceName(scrn_idx %d) -> NULL\n", scrn_idx);
        return (*env)->NewIntArray(env, 0);
    }

    int devModeID;
    int widthmm, heightmm;
    if(-1 < mode_idx) {
        // only at initialization time, where index >= 0
        HDC hdc = NewtScreen_createDisplayDC(deviceName);
        widthmm = GetDeviceCaps(hdc, HORZSIZE);
        heightmm = GetDeviceCaps(hdc, VERTSIZE);
        DeleteDC(hdc);
        devModeID = (int) mode_idx;
        prop_num++; // add 1st extra prop, mode_idx
    } else {
        widthmm = 0;
        heightmm = 0;
        devModeID = ENUM_CURRENT_SETTINGS;
    }

    DEVMODE dm;
    ZeroMemory(&dm, sizeof(dm));
    dm.dmSize = sizeof(dm);
    
    if (0 == EnumDisplaySettingsEx(deviceName, devModeID, &dm, ( ENUM_CURRENT_SETTINGS == devModeID ) ? 0 : EDS_ROTATEDMODE)) {
        DBG_PRINT("*** WindowsWindow: getScreenMode.EnumDisplaySettingsEx(mode_idx %d/%d) -> NULL\n", mode_idx, devModeID);
        return (*env)->NewIntArray(env, 0);
    }
    
    // swap width and height, since Windows reflects rotated dimension, we don't
    if (DMDO_90 == dm.dmDisplayOrientation || DMDO_270 == dm.dmDisplayOrientation) {
        int tempWidth = dm.dmPelsWidth;
        dm.dmPelsWidth = dm.dmPelsHeight;
        dm.dmPelsHeight = tempWidth;
    }

    jint prop[ prop_num ];
    int propIndex = 0;

    if( -1 < mode_idx ) {
        prop[propIndex++] = mode_idx;
    }
    prop[propIndex++] = 0; // set later for verification of iterator
    prop[propIndex++] = dm.dmPelsWidth;
    prop[propIndex++] = dm.dmPelsHeight;
    prop[propIndex++] = dm.dmBitsPerPel;
    prop[propIndex++] = widthmm;
    prop[propIndex++] = heightmm;
    prop[propIndex++] = dm.dmDisplayFrequency;
    prop[propIndex++] = NewtScreen_RotationNativeCCW2NewtCCW(env, dm.dmDisplayOrientation);
    prop[propIndex - NUM_SCREEN_MODE_PROPERTIES_ALL] = ( -1 < mode_idx ) ? propIndex-1 : propIndex ; // count == NUM_SCREEN_MODE_PROPERTIES_ALL

    jintArray properties = (*env)->NewIntArray(env, prop_num);
    if (properties == NULL) {
        NewtCommon_throwNewRuntimeException(env, "Could not allocate int array of size %d", prop_num);
    }
    (*env)->SetIntArrayRegion(env, properties, 0, prop_num, prop);
    
    return properties;
}

/*
 * Class:     jogamp_newt_driver_windows_ScreenDriver
 * Method:    setScreenMode0
 * Signature: (IIIIII)Z
 */
JNIEXPORT jboolean JNICALL Java_jogamp_newt_driver_windows_ScreenDriver_setScreenMode0
  (JNIEnv *env, jobject object, jint scrn_idx, jint width, jint height, jint bits, jint rate, jint rot)
{
    DISPLAY_DEVICE device;
    LPCTSTR deviceName = NewtScreen_getDisplayDeviceName(&device, scrn_idx);
    if(NULL == deviceName) {
        DBG_PRINT("*** WindowsWindow: setScreenMode.getDisplayDeviceName(scrn_idx %d) -> NULL\n", scrn_idx);
        return JNI_FALSE;
    }

    DEVMODE dm;
    // initialize the DEVMODE structure
    ZeroMemory(&dm, sizeof(dm));
    dm.dmSize = sizeof(dm);
    dm.dmPelsWidth = (int)width;
    dm.dmPelsHeight = (int)height;
    dm.dmBitsPerPel = (int)bits;
    dm.dmDisplayFrequency = (int)rate;
    dm.dmDisplayOrientation = NewtScreen_RotationNewtCCW2NativeCCW(env, rot);

    // swap width and height, since Windows reflects rotated dimension, we don't
    if ( DMDO_90 == dm.dmDisplayOrientation || DMDO_270 == dm.dmDisplayOrientation ) {
        int tempWidth = dm.dmPelsWidth;
        dm.dmPelsWidth = dm.dmPelsHeight;
        dm.dmPelsHeight = tempWidth;
    }

    dm.dmFields = DM_DISPLAYORIENTATION | DM_PELSWIDTH | DM_PELSHEIGHT | DM_BITSPERPEL | DM_DISPLAYFREQUENCY;
    
    return ( DISP_CHANGE_SUCCESSFUL == ChangeDisplaySettings(&dm, 0) ) ? JNI_TRUE : JNI_FALSE ;
}

/*
 * Class:     jogamp_newt_driver_windows_WindowDriver
 * Method:    initIDs0
 * Signature: ()Z
 */
JNIEXPORT jboolean JNICALL Java_jogamp_newt_driver_windows_WindowDriver_initIDs0
  (JNIEnv *env, jclass clazz, jlong hInstance)
{
    NewtCommon_init(env);

    insetsChangedID = (*env)->GetMethodID(env, clazz, "insetsChanged", "(ZIIII)V");
    sizeChangedID = (*env)->GetMethodID(env, clazz, "sizeChanged", "(ZIIZ)V");
    positionChangedID = (*env)->GetMethodID(env, clazz, "positionChanged", "(ZII)V");
    focusChangedID = (*env)->GetMethodID(env, clazz, "focusChanged", "(ZZ)V");
    visibleChangedID = (*env)->GetMethodID(env, clazz, "visibleChanged", "(ZZ)V");
    windowDestroyNotifyID = (*env)->GetMethodID(env, clazz, "windowDestroyNotify", "(Z)Z");
    windowRepaintID = (*env)->GetMethodID(env, clazz, "windowRepaint", "(ZIIII)V");
    enqueueMouseEventID = (*env)->GetMethodID(env, clazz, "enqueueMouseEvent", "(ZIIIIIF)V");
    sendMouseEventID = (*env)->GetMethodID(env, clazz, "sendMouseEvent", "(IIIIIF)V");
    enqueueKeyEventID = (*env)->GetMethodID(env, clazz, "enqueueKeyEvent", "(ZIIIC)V");
    sendKeyEventID = (*env)->GetMethodID(env, clazz, "sendKeyEvent", "(IIIC)V");
    requestFocusID = (*env)->GetMethodID(env, clazz, "requestFocus", "(Z)V");

    if (insetsChangedID == NULL ||
        sizeChangedID == NULL ||
        positionChangedID == NULL ||
        focusChangedID == NULL ||
        visibleChangedID == NULL ||
        windowDestroyNotifyID == NULL ||
        windowRepaintID == NULL ||
        enqueueMouseEventID == NULL ||
        sendMouseEventID == NULL ||
        enqueueKeyEventID == NULL ||
        sendKeyEventID == NULL ||
        requestFocusID == NULL) {
        return JNI_FALSE;
    }
    BuildDynamicKeyMapTable();

    return JNI_TRUE;
}

/*
 * Class:     jogamp_newt_driver_windows_WindowDriver
 * Method:    getNewtWndProc0
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL Java_jogamp_newt_driver_windows_WindowDriver_getNewtWndProc0
  (JNIEnv *env, jclass clazz)
{
    return (jlong) (intptr_t) wndProc;
}

static void NewtWindow_setVisiblePosSize(HWND hwnd, BOOL atop, BOOL visible, 
                                         int x, int y, int width, int height)
{
    UINT flags;
    BOOL bRes;
    
    DBG_PRINT("*** WindowsWindow: NewtWindow_setVisiblePosSize %d/%d %dx%d, atop %d, visible %d\n", 
        x, y, width, height, atop, visible);

    if(visible) {
        flags = SWP_SHOWWINDOW;
    } else {
        flags = SWP_NOACTIVATE | SWP_NOZORDER;
    }
    if(0>=width || 0>=height ) {
        flags |= SWP_NOSIZE;
    }

    if(atop) {
        SetWindowPos(hwnd, HWND_TOP, x, y, width, height, flags);
        SetWindowPos(hwnd, HWND_TOPMOST, x, y, width, height, flags);
    } else {
        SetWindowPos(hwnd, HWND_NOTOPMOST, x, y, width, height, flags);
        SetWindowPos(hwnd, HWND_TOP, x, y, width, height, flags);
    }
    // SetWindowPos(hwnd, atop ? HWND_TOPMOST : HWND_TOP, x, y, width, height, flags);

    InvalidateRect(hwnd, NULL, TRUE);
    UpdateWindow(hwnd);
}

#define WS_DEFAULT_STYLES (WS_CLIPSIBLINGS | WS_CLIPCHILDREN | WS_TABSTOP)

/*
 * Class:     jogamp_newt_driver_windows_WindowDriver
 * Method:    CreateWindow
 */
JNIEXPORT jlong JNICALL Java_jogamp_newt_driver_windows_WindowDriver_CreateWindow0
  (JNIEnv *env, jobject obj, 
   jlong hInstance, jstring jWndClassName, jstring jWndName, 
   jlong parent,
   jint jx, jint jy, jint defaultWidth, jint defaultHeight, jboolean autoPosition, jint flags)
{
    HWND parentWindow = (HWND) (intptr_t) parent;
    const TCHAR* wndClassName = NULL;
    const TCHAR* wndName = NULL;
    DWORD windowStyle = WS_DEFAULT_STYLES | WS_VISIBLE;
    int x=(int)jx, y=(int)jy;
    int width=(int)defaultWidth, height=(int)defaultHeight;
    HWND window = NULL;
    int _x = x, _y = y; // pos for CreateWindow, might be tweaked

#ifdef UNICODE
    wndClassName = NewtCommon_GetNullTerminatedStringChars(env, jWndClassName);
    wndName = NewtCommon_GetNullTerminatedStringChars(env, jWndName);
#else
    wndClassName = (*env)->GetStringUTFChars(env, jWndClassName, NULL);
    wndName = (*env)->GetStringUTFChars(env, jWndName, NULL);
#endif

    if( NULL!=parentWindow ) {
        if (!IsWindow(parentWindow)) {
            DBG_PRINT("*** WindowsWindow: CreateWindow failure: Passed parentWindow %p is invalid\n", parentWindow);
            return 0;
        }
        windowStyle |= WS_CHILD ;
    } else if ( TST_FLAG_IS_UNDECORATED(flags) ) {
        windowStyle |= WS_POPUP | WS_SYSMENU | WS_MAXIMIZEBOX | WS_MINIMIZEBOX;
    } else {
        windowStyle |= WS_OVERLAPPEDWINDOW;
        if(JNI_TRUE == autoPosition) {
            // user didn't requested specific position, use WM default
            _x = CW_USEDEFAULT;
            _y = 0;
        }
    }

    window = CreateWindow(wndClassName, wndName, windowStyle,
                          _x, _y, width, height,
                          parentWindow, NULL,
                          (HINSTANCE) (intptr_t) hInstance,
                          NULL);

    DBG_PRINT("*** WindowsWindow: CreateWindow thread 0x%X, parent %p, window %p, %d/%d %dx%d, undeco %d, alwaysOnTop %d, autoPosition %d\n", 
        (int)GetCurrentThreadId(), parentWindow, window, x, y, width, height,
        TST_FLAG_IS_UNDECORATED(flags), TST_FLAG_IS_ALWAYSONTOP(flags), autoPosition);

    if (NULL == window) {
        int lastError = (int) GetLastError();
        DBG_PRINT("*** WindowsWindow: CreateWindow failure: 0x%X %d\n", lastError, lastError);
        return 0;
    } else {
        WindowUserData * wud = (WindowUserData *) malloc(sizeof(WindowUserData));
        wud->jinstance = (*env)->NewGlobalRef(env, obj);
        wud->jenv = env;
#if !defined(__MINGW64__) && ( defined(UNDER_CE) || _MSC_VER <= 1200 )
        SetWindowLong(window, GWL_USERDATA, (intptr_t) wud);
#else
        SetWindowLongPtr(window, GWLP_USERDATA, (intptr_t) wud);
#endif

        // gather and adjust position and size
        {
            RECT rc;
            RECT * insets;

            ShowWindow(window, SW_SHOW);

            // send insets before visibility, allowing java code a proper sync point!
            insets = UpdateInsets(env, wud->jinstance, window);
            (*env)->CallVoidMethod(env, wud->jinstance, visibleChangedID, JNI_FALSE, JNI_TRUE);

            if(JNI_TRUE == autoPosition) {
                GetWindowRect(window, &rc);
                x = rc.left + insets->left; // client coords
                y = rc.top + insets->top;   // client coords
            }
            DBG_PRINT("*** WindowsWindow: CreateWindow client: %d/%d %dx%d (autoPosition %d)\n", x, y, width, height, autoPosition);

            x -= insets->left; // top-level
            y -= insets->top;  // top-level
            width += insets->left + insets->right;   // top-level
            height += insets->top + insets->bottom;  // top-level
            DBG_PRINT("*** WindowsWindow: CreateWindow top-level %d/%d %dx%d\n", x, y, width, height);

            NewtWindow_setVisiblePosSize(window, TST_FLAG_IS_ALWAYSONTOP(flags), TRUE, x, y, width, height);
        }
    }

#ifdef UNICODE
    free((void*) wndClassName);
    free((void*) wndName);
#else
    (*env)->ReleaseStringUTFChars(env, jWndClassName, wndClassName);
    (*env)->ReleaseStringUTFChars(env, jWndName, wndName);
#endif

#ifdef TEST_MOUSE_HOOKS
    hookLLMP = SetWindowsHookEx(WH_MOUSE_LL, &HookLowLevelMouseProc, (HINSTANCE) (intptr_t) hInstance, 0);
    hookMP = SetWindowsHookEx(WH_MOUSE_LL, &HookMouseProc, (HINSTANCE) (intptr_t) hInstance, 0);
    DBG_PRINT("**** LLMP Hook %p, MP Hook %p\n", hookLLMP, hookMP);
#endif

    return (jlong) (intptr_t) window;
}

/*
 * Class:     jogamp_newt_driver_windows_WindowDriver
 * Method:    MonitorFromWindow
 * Signature: (J)J
 */
JNIEXPORT jlong JNICALL Java_jogamp_newt_driver_windows_WindowDriver_MonitorFromWindow0
  (JNIEnv *env, jobject obj, jlong window)
{
    #if (_WIN32_WINNT >= 0x0500 || _WIN32_WINDOWS >= 0x0410 || WINVER >= 0x0500) && !defined(_WIN32_WCE)
        return (jlong) (intptr_t) MonitorFromWindow((HWND) (intptr_t) window, MONITOR_DEFAULTTOPRIMARY);
    #else
        return 0;
    #endif
}

static jboolean NewtWindows_setFullScreen(jboolean fullscreen)
{
    int flags = 0;
    DEVMODE dm;
    // initialize the DEVMODE structure
    ZeroMemory(&dm, sizeof(dm));
    dm.dmSize = sizeof(dm);

    if (0 == EnumDisplaySettings(NULL /*current display device*/, ENUM_CURRENT_SETTINGS, &dm))
    {
        return JNI_FALSE;
    }
    
    flags = ( JNI_TRUE == fullscreen ) ? CDS_FULLSCREEN : CDS_RESET ;

    return ( DISP_CHANGE_SUCCESSFUL == ChangeDisplaySettings(&dm, flags) ) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     jogamp_newt_driver_windows_WindowDriver
 * Method:    reconfigureWindow0
 * Signature: (JJIIIII)V
 */
JNIEXPORT void JNICALL Java_jogamp_newt_driver_windows_WindowDriver_reconfigureWindow0
  (JNIEnv *env, jobject obj, jlong parent, jlong window,
   jint x, jint y, jint width, jint height, jint flags)
{
    HWND hwndP = (HWND) (intptr_t) parent;
    HWND hwnd = (HWND) (intptr_t) window;
    DWORD windowStyle = WS_DEFAULT_STYLES;
    BOOL styleChange = TST_FLAG_CHANGE_DECORATION(flags) || TST_FLAG_CHANGE_FULLSCREEN(flags) || TST_FLAG_CHANGE_PARENTING(flags) ;

    DBG_PRINT( "*** WindowsWindow: reconfigureWindow0 parent %p, window %p, %d/%d %dx%d, parentChange %d, hasParent %d, decorationChange %d, undecorated %d, fullscreenChange %d, fullscreen %d, alwaysOnTopChange %d, alwaysOnTop %d, visibleChange %d, visible %d -> styleChange %d\n",
        parent, window, x, y, width, height,
        TST_FLAG_CHANGE_PARENTING(flags),   TST_FLAG_HAS_PARENT(flags),
        TST_FLAG_CHANGE_DECORATION(flags),  TST_FLAG_IS_UNDECORATED(flags),
        TST_FLAG_CHANGE_FULLSCREEN(flags),  TST_FLAG_IS_FULLSCREEN(flags),
        TST_FLAG_CHANGE_ALWAYSONTOP(flags), TST_FLAG_IS_ALWAYSONTOP(flags),
        TST_FLAG_CHANGE_VISIBILITY(flags), TST_FLAG_IS_VISIBLE(flags), styleChange);

    if (!IsWindow(hwnd)) {
        DBG_PRINT("*** WindowsWindow: reconfigureWindow0 failure: Passed window %p is invalid\n", (void*)hwnd);
        return;
    }

    if (NULL!=hwndP && !IsWindow(hwndP)) {
        DBG_PRINT("*** WindowsWindow: reconfigureWindow0 failure: Passed parent window %p is invalid\n", (void*)hwndP);
        return;
    }

    if(TST_FLAG_IS_VISIBLE(flags)) {
        windowStyle |= WS_VISIBLE ;
    }
    
    // order of call sequence: (MS documentation)
    //    TOP:  SetParent(.., NULL); Clear WS_CHILD [, Set WS_POPUP]
    //  CHILD:  Set WS_CHILD [, Clear WS_POPUP]; SetParent(.., PARENT) 
    //
    if( TST_FLAG_CHANGE_PARENTING(flags) && NULL == hwndP ) {
        // TOP: in -> out
        SetParent(hwnd, NULL);
    }
    
    if( TST_FLAG_CHANGE_FULLSCREEN(flags) && TST_FLAG_IS_FULLSCREEN(flags) ) { // FS on
        // TOP: in -> out
        NewtWindows_setFullScreen(JNI_TRUE);
    }

    if ( styleChange ) {
        if(NULL!=hwndP) {
            windowStyle |= WS_CHILD ;
        } else if ( TST_FLAG_IS_UNDECORATED(flags) ) {
            windowStyle |= WS_POPUP | WS_SYSMENU | WS_MAXIMIZEBOX | WS_MINIMIZEBOX;
        } else {
            windowStyle |= WS_OVERLAPPEDWINDOW;
        }
        SetWindowLong(hwnd, GWL_STYLE, windowStyle);
        SetWindowPos(hwnd, 0, 0, 0, 0, 0, SWP_FRAMECHANGED | SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOZORDER );
    }

    if( TST_FLAG_CHANGE_FULLSCREEN(flags) && !TST_FLAG_IS_FULLSCREEN(flags) ) { // FS off
        // CHILD: out -> in
        NewtWindows_setFullScreen(JNI_FALSE);
    }

    if ( TST_FLAG_CHANGE_PARENTING(flags) && NULL != hwndP ) {
        // CHILD: out -> in
        SetParent(hwnd, hwndP );
    }

    NewtWindow_setVisiblePosSize(hwnd, TST_FLAG_IS_ALWAYSONTOP(flags), TST_FLAG_IS_VISIBLE(flags), x, y, width, height);

    if( TST_FLAG_CHANGE_VISIBILITY(flags) ) {
        if( TST_FLAG_IS_VISIBLE(flags) ) {
            ShowWindow(hwnd, SW_SHOW);
        } else {
            ShowWindow(hwnd, SW_HIDE);
        }
    }

    DBG_PRINT("*** WindowsWindow: reconfigureWindow0.X\n");
}

/*
 * Class:     jogamp_newt_driver_windows_WindowDriver
 * Method:    setTitle
 * Signature: (JLjava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_jogamp_newt_driver_windows_WindowDriver_setTitle0
  (JNIEnv *env, jclass clazz, jlong window, jstring title)
{
    HWND hwnd = (HWND) (intptr_t) window;
    if (title != NULL) {
        jchar *titleString = NewtCommon_GetNullTerminatedStringChars(env, title);
        if (titleString != NULL) {
            SetWindowTextW(hwnd, titleString);
            free(titleString);
        }
    }
}

/*
 * Class:     jogamp_newt_driver_windows_WindowDriver
 * Method:    requestFocus
 * Signature: (JZ)V
 */
JNIEXPORT void JNICALL Java_jogamp_newt_driver_windows_WindowDriver_requestFocus0
  (JNIEnv *env, jobject obj, jlong window, jboolean force)
{
    DBG_PRINT("*** WindowsWindow: RequestFocus0\n");
    NewtWindows_requestFocus ( env, obj, (HWND) (intptr_t) window, force) ;
}

/*
 * Class:     Java_jogamp_newt_driver_windows_WindowDriver
 * Method:    setPointerVisible0
 * Signature: (JJZ)Z
 */
JNIEXPORT jboolean JNICALL Java_jogamp_newt_driver_windows_WindowDriver_setPointerVisible0
  (JNIEnv *env, jclass clazz, jlong window, jboolean mouseVisible)
{
    HWND hwnd = (HWND) (intptr_t) window;
    int res, resOld, i;
    jboolean b;

    if(JNI_TRUE == mouseVisible) {
        res = ShowCursor(TRUE);
        if(res < 0) {
            i=0;
            do {
                resOld = res;
                res = ShowCursor(TRUE);
            } while(res!=resOld && res<0 && ++i<10);
        }
        b = res>=0 ? JNI_TRUE : JNI_FALSE;
    } else {
        res = ShowCursor(FALSE);
        if(res >= 0) {
            i=0;
            do {
                resOld = res;
                res = ShowCursor(FALSE);
            } while(res!=resOld && res>=0 && ++i<10);
        }
        b = res<0 ? JNI_TRUE : JNI_FALSE;
    }

    DBG_PRINT( "*** WindowsWindow: setPointerVisible0: %d, res %d/%d\n", mouseVisible, res, b);

    return b;
}

/*
 * Class:     Java_jogamp_newt_driver_windows_WindowDriver
 * Method:    confinePointer0
 * Signature: (JJZIIII)Z
 */
JNIEXPORT jboolean JNICALL Java_jogamp_newt_driver_windows_WindowDriver_confinePointer0
  (JNIEnv *env, jclass clazz, jlong window, jboolean confine, jint l, jint t, jint r, jint b)
{
    HWND hwnd = (HWND) (intptr_t) window;
    jboolean res;

    if(JNI_TRUE == confine) {
        // SetCapture(hwnd);
        // res = ( GetCapture() == hwnd ) ? JNI_TRUE : JNI_FALSE;
        RECT rect = { l, t, r, b };
        res = ClipCursor(&rect) ? JNI_TRUE : JNI_FALSE;
    } else {
        // res = ReleaseCapture() ? JNI_TRUE : JNI_FALSE;
        res = ClipCursor(NULL) ? JNI_TRUE : JNI_FALSE;
    }
    DBG_PRINT( "*** WindowsWindow: confinePointer0: %d, [ l %d t %d r %d b %d ], res %d\n", 
        confine, l, t, r, b, res);

    return res;
}

/*
 * Class:     Java_jogamp_newt_driver_windows_WindowDriver
 * Method:    warpPointer0
 * Signature: (JJII)V
 */
JNIEXPORT void JNICALL Java_jogamp_newt_driver_windows_WindowDriver_warpPointer0
  (JNIEnv *env, jclass clazz, jlong window, jint x, jint y)
{
    DBG_PRINT( "*** WindowsWindow: warpPointer0: %d/%d\n", x, y);
    SetCursorPos(x, y);
}

/*
 * Class:     Java_jogamp_newt_driver_windows_WindowDriver
 * Method:    trackPointerLeave0
 * Signature: (J)V
 */
JNIEXPORT void JNICALL Java_jogamp_newt_driver_windows_WindowDriver_trackPointerLeave0
  (JNIEnv *env, jclass clazz, jlong window)
{
    HWND hwnd = (HWND) (intptr_t) window;
    DBG_PRINT( "*** WindowsWindow: trackMouseLeave0\n");
    NewtWindows_trackPointerLeave(hwnd);
}

