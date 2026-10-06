/* 
 * Copyright (c) Microsoft
 * Copyright (c) 2024 Eclipse Foundation
 * 
 *  This program and the accompanying materials are made available 
 *  under the terms of the MIT license which is available at
 *  https://opensource.org/license/mit.
 * 
 *  SPDX-License-Identifier: MIT
 * 
 *  Contributors: 
 *     Microsoft         - Initial version
 *     Frédéric Desbiens - 2024 version.
 */

#include "screen.h"

#include "ssd1306.h"

void screen_print(char* str, LINE_NUM line)
{
    ssd1306_Fill(Black);
    ssd1306_SetCursor(2, line);
    ssd1306_WriteString(str, Font_11x18, White);
    ssd1306_UpdateScreen();
}

void screen_printn(const char* str, unsigned int str_length, LINE_NUM line)
{
    ssd1306_Fill(Black);
    ssd1306_SetCursor(2, line);

    for (unsigned int i = 0; i < str_length; ++i)
    {
        if (ssd1306_WriteChar(str[i], Font_11x18, White) != str[i])
        {
            return;
        }
    }

    ssd1306_UpdateScreen();
}

void screen_print_wrapped(const char* header, const char* str, unsigned int str_length)
{
    /* SSD1306 is 128x64; with Font_11x18 that is ~11 chars/line and 4 lines. */
    const unsigned int CHARS_PER_LINE = 11;
    const LINE_NUM      body_lines[3] = {L1, L2, L3};

    ssd1306_Fill(Black);

    /* Header on the top line. */
    ssd1306_SetCursor(2, L0);
    for (const char* p = header; p != NULL && *p != '\0'; ++p)
    {
        if (ssd1306_WriteChar(*p, Font_11x18, White) != *p)
        {
            break;
        }
    }

    /* Body wrapped across the remaining three lines. */
    for (unsigned int line = 0; line < 3; ++line)
    {
        unsigned int offset = line * CHARS_PER_LINE;
        if (offset >= str_length)
        {
            break;
        }

        unsigned int remaining = str_length - offset;
        unsigned int chunk     = remaining < CHARS_PER_LINE ? remaining : CHARS_PER_LINE;

        ssd1306_SetCursor(2, body_lines[line]);
        for (unsigned int i = 0; i < chunk; ++i)
        {
            char c = str[offset + i];
            /* Render non-printable characters (e.g. newlines) as spaces. */
            if (c < 0x20 || c > 0x7E)
            {
                c = ' ';
            }
            if (ssd1306_WriteChar(c, Font_11x18, White) != c)
            {
                break; /* ran out of horizontal space on this line */
            }
        }
    }

    ssd1306_UpdateScreen();
}