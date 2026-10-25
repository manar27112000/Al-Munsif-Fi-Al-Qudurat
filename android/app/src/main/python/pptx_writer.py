# -*- coding: utf-8 -*-
"""كاتب PPTX بدون أي مكتبات خارجية (zipfile + XML بس)."""
import base64
import io
import re
import zipfile
from xml.sax.saxutils import escape

import munsif_core as core
from template_data import TEMPLATE_B64

EMU = 914400
SLIDE_W = 13.333
SLIDE_H = 7.5
MARGIN = 0.5
FONT = "Arial"

_CTRL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f￾￿]")

NS = (
    'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"'
)


def _emu(inches):
    return int(round(inches * EMU))


def _esc(s):
    return escape(_CTRL_RE.sub("", s))


def _rpr(size_pt, bold, italic, rtl, color="1F2933"):
    lang = "ar-SA" if rtl else "en-US"
    return (
        '<a:rPr lang="%s" sz="%d" b="%d" i="%d" dirty="0">'
        '<a:solidFill><a:srgbClr val="%s"/></a:solidFill>'
        '<a:latin typeface="%s"/><a:cs typeface="%s"/></a:rPr>'
    ) % (lang, int(size_pt * 100), 1 if bold else 0, 1 if italic else 0, color, FONT, FONT)


def _paragraph(runs, size_pt, rtl, space_after_pt=8):
    ppr = '<a:pPr algn="%s" rtl="%d"><a:spcAft><a:spcPts val="%d"/></a:spcAft></a:pPr>' % (
        "r" if rtl else "l", 1 if rtl else 0, space_after_pt * 100)
    body = []
    for r in runs:
        rpr = _rpr(size_pt, r["bold"], r["italic"], rtl)
        parts = r["text"].split("\n")
        for i, part in enumerate(parts):
            if i > 0:
                body.append("<a:br>%s</a:br>" % rpr)
            if part != "":
                body.append("<a:r>%s<a:t>%s</a:t></a:r>" % (rpr, _esc(part)))
    end = '<a:endParaRPr lang="%s" sz="%d" dirty="0"/>' % ("ar-SA" if rtl else "en-US", int(size_pt * 100))
    return "<a:p>%s%s%s</a:p>" % (ppr, "".join(body), end)


def _textbox(shape_id, name, x, y, w, h, paragraphs_xml, inset_in=0.083):
    ins = _emu(inset_in)
    return (
        '<p:sp><p:nvSpPr><p:cNvPr id="%d" name="%s"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>'
        '<p:spPr><a:xfrm><a:off x="%d" y="%d"/><a:ext cx="%d" cy="%d"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>'
        '<p:txBody><a:bodyPr wrap="square" lIns="%d" tIns="%d" rIns="%d" bIns="%d" rtlCol="0" anchor="t">'
        '<a:noAutofit/></a:bodyPr><a:lstStyle/>%s</p:txBody></p:sp>'
    ) % (shape_id, name, _emu(x), _emu(y), _emu(w), _emu(h), ins, ins, ins, ins, paragraphs_xml)


def _picture(shape_id, rid, x, y, w, h):
    return (
        '<p:pic><p:nvPicPr><p:cNvPr id="%d" name="Picture %d"/>'
        '<p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>'
        '<p:blipFill><a:blip r:embed="%s"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>'
        '<p:spPr><a:xfrm><a:off x="%d" y="%d"/><a:ext cx="%d" cy="%d"/></a:xfrm>'
        '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>'
    ) % (shape_id, shape_id, rid, _emu(x), _emu(y), _emu(w), _emu(h))


def _slide_xml(shapes_xml):
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<p:sld %s><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill>'
        '<a:effectLst/></p:bgPr></p:bg><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/>'
        '<p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/>'
        '<a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>'
        '%s</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>'
    ) % (NS, shapes_xml)


_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
_IMG_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
_LAYOUT_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"
_SLIDE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"

_IMG_TYPES = {"jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif"}


def build_pptx(groups, out_path, title="", on_slide=None):
    """groups -> ملف pptx. بيرجّع عدد الصور اللي اتخطّت."""
    base = zipfile.ZipFile(io.BytesIO(base64.b64decode(TEMPLATE_B64)))
    parts = {n: base.read(n) for n in base.namelist()}

    content_w = SLIDE_W - 2 * MARGIN
    footer_h = 0.4
    content_h = SLIDE_H - 2 * MARGIN - footer_h + 0.3
    total = len(groups)
    skipped = 0

    media = {}          # اسم الملف جوه الباكدج -> bytes
    media_by_path = {}  # مسار الصورة الأصلي -> (اسم الميديا، ext)
    media_exts = set()
    slide_entries = []

    for gi, g in enumerate(groups):
        shapes = []
        rels = ['<Relationship Id="rId1" Type="%s" Target="../slideLayouts/slideLayout7.xml"/>' % _LAYOUT_REL]
        next_id = 2
        images = []   # (rid, w, h)
        blocks = []
        for it in g["items"]:
            if it["image"]:
                path = it["image"]
                if path not in media_by_path:
                    loaded = core.load_image(path)
                    if loaded is None:
                        media_by_path[path] = None
                    else:
                        data, ext, w, h = loaded
                        name = "image%d.%s" % (len(media) + 1, "jpeg" if ext == "jpeg" else ext)
                        media[name] = data
                        media_exts.add(ext)
                        media_by_path[path] = (name, w, h)
                info = media_by_path[path]
                if info is None:
                    skipped += 1
                else:
                    rid = "rId%d" % (len(rels) + 1)
                    rels.append('<Relationship Id="%s" Type="%s" Target="../media/%s"/>' % (rid, _IMG_REL, info[0]))
                    images.append((rid, info[1], info[2]))
            if it["runs"]:
                blocks.append(it["runs"])
            if it["note"]:
                blocks.append([{"text": it["note"], "bold": False, "italic": True}])

        cursor_y = MARGIN
        if images:
            max_img_h = content_h * (0.55 if blocks else 0.92)
            gap = 0.12
            row_h, widths = core.layout_image_row([(w, h) for _, w, h in images], content_w, max_img_h, gap)
            total_row_w = sum(widths) + gap * (len(images) - 1)
            x = MARGIN + (content_w - total_row_w) / 2
            for (rid, _, _), w in zip(images, widths):
                shapes.append(_picture(next_id, rid, x, cursor_y, w, row_h))
                next_id += 1
                x += w + gap
            cursor_y += row_h + 0.22

        if blocks:
            box_h = max(0.6, MARGIN + content_h - cursor_y)
            paragraphs = [core.plain_of(b) for b in blocks]
            font = core.pick_font_size(paragraphs, content_w, box_h)
            paras_xml = []
            for b in blocks:
                rtl = core.arabic_ratio(core.plain_of(b)) >= 0.3
                paras_xml.append(_paragraph(b, font, rtl))
            shapes.append(_textbox(next_id, "Text %d" % next_id, MARGIN, cursor_y, content_w, box_h, "".join(paras_xml)))
            next_id += 1

        foot = _paragraph([{"text": "%d/%d" % (gi + 1, total), "bold": False, "italic": False}], 10, False, 0)
        foot = foot.replace('<a:pPr algn="l"', '<a:pPr algn="r"').replace("1F2933", "8A94A0")
        shapes.append(_textbox(next_id, "Footer", SLIDE_W - MARGIN - 1.2, SLIDE_H - 0.42, 1.2, 0.3, foot, inset_in=0))

        n = gi + 1
        parts["ppt/slides/slide%d.xml" % n] = _slide_xml("".join(shapes)).encode("utf-8")
        parts["ppt/slides/_rels/slide%d.xml.rels" % n] = (
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="%s">%s</Relationships>'
            % (_REL_NS, "".join(rels))
        ).encode("utf-8")
        slide_entries.append(n)
        if on_slide:
            on_slide(n, total)

    # --- presentation.xml + rels + content types ---
    pres = parts["ppt/presentation.xml"].decode("utf-8")
    prels = parts["ppt/_rels/presentation.xml.rels"].decode("utf-8")
    ctypes = parts["[Content_Types].xml"].decode("utf-8")

    id_xml = []
    add_rels = []
    ct_over = []
    for n in slide_entries:
        rid = "rId%d" % (100 + n)
        id_xml.append('<p:sldId id="%d" r:id="%s"/>' % (255 + n, rid))
        add_rels.append('<Relationship Id="%s" Type="%s" Target="slides/slide%d.xml"/>' % (rid, _SLIDE_REL, n))
        ct_over.append('<Override PartName="/ppt/slides/slide%d.xml" '
                       'ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>' % n)
    if id_xml:
        pres = pres.replace("</p:sldMasterIdLst>", "</p:sldMasterIdLst><p:sldIdLst>%s</p:sldIdLst>" % "".join(id_xml), 1)
    prels = prels.replace("</Relationships>", "".join(add_rels) + "</Relationships>")
    defaults = ""
    for ext in sorted(media_exts):
        tag = 'Extension="%s"' % ext
        if tag not in ctypes:
            defaults += '<Default Extension="%s" ContentType="%s"/>' % (ext, _IMG_TYPES[ext])
    ctypes = ctypes.replace("<Override ", defaults + "<Override ", 1) if defaults else ctypes
    ctypes = ctypes.replace("</Types>", "".join(ct_over) + "</Types>")

    parts["ppt/presentation.xml"] = pres.encode("utf-8")
    parts["ppt/_rels/presentation.xml.rels"] = prels.encode("utf-8")
    parts["[Content_Types].xml"] = ctypes.encode("utf-8")

    if title:
        core_xml = parts["docProps/core.xml"].decode("utf-8")
        if "<dc:title>" in core_xml or "<dc:title/>" in core_xml:
            core_xml = re.sub(r"<dc:title>.*?</dc:title>|<dc:title/>", "<dc:title>%s</dc:title>" % _esc(title), core_xml, 1)
        else:
            core_xml = core_xml.replace("</cp:coreProperties>", "<dc:title>%s</dc:title></cp:coreProperties>" % _esc(title))
        parts["docProps/core.xml"] = core_xml.encode("utf-8")

    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        # [Content_Types].xml لازم يكون أول ملف
        z.writestr("[Content_Types].xml", parts.pop("[Content_Types].xml"))
        for n, d in parts.items():
            z.writestr(n, d)
        for n, d in media.items():
            z.writestr("ppt/media/" + n, d, compress_type=zipfile.ZIP_STORED)
    return skipped
