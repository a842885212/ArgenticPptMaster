#!/usr/bin/env python3
"""Generate the PPTX fixture corpus for PPTX→Markdown read-back parity tests.

Ground truth for every emitted structure is `source_to_md/ppt_to_md.py` in the
ppt-master repo (pinned by the dual-run tests); these decks only exercise the
features the parity spec covers:

- hyperlinks.pptx: run-level external URL, slide-internal jump (hlinksldjump),
  shape-level click_action (external + named-slide), dangerous scheme dropped,
  consecutive runs sharing one URL merged mid-sentence.
- charts.pptx: clustered bar / line / pie charts with categories + series.
- tables-images.pptx: native table, one PNG reused on two slides (manifest
  dedup + occurrences), speaker notes, plain multi-paragraph text.

Requires: pip install "python-pptx<0.7" pillow
"""

from pathlib import Path

from pptx import Presentation
from pptx.chart.data import CategoryChartData
from pptx.enum.action import PP_ACTION
from pptx.enum.shapes import MSO_SHAPE_TYPE
from pptx.enum.text import PP_ALIGN
from pptx.opc.constants import RELATIONSHIP_TYPE as RT
from pptx.oxml.ns import qn
from pptx.util import Emu, Inches, Pt

OUTPUT_DIR = Path("src/test/resources/readback")


def add_textbox(slide, left, top, width, height):
    return slide.shapes.add_textbox(Inches(left), Inches(top), Inches(width), Inches(height))


def set_run_link(run, url):
    run.hyperlink.address = url


def set_run_internal_jump(run, slide, target_slide):
    """Attach a ppaction://hlinksldjump hyperlink via lxml (no public API)."""
    r_id = slide.part.relate_to(target_slide.part, RT.SLIDE)
    rpr = run._r.get_or_add_rPr()
    for existing in rpr.findall(qn("a:hlinkClick")):
        rpr.remove(existing)
    hlink = rpr.makeelement(qn("a:hlinkClick"), {})
    hlink.set("action", "ppaction://hlinksldjump")
    hlink.set(qn("r:id"), r_id)
    rpr.append(hlink)


def build_hyperlinks(path: Path) -> None:
    prs = Presentation()
    blank = prs.slide_layouts[6]
    s1, s2, s3 = (prs.slides.add_slide(blank) for _ in range(3))

    box = add_textbox(s1, 1, 1, 8, 2)
    p = box.text_frame.paragraphs[0]
    run = p.add_run()
    run.text = "External link"
    set_run_link(run, "https://example.com/docs?a=1&b=2")

    p2 = box.text_frame.add_paragraph()
    for text, url in (("See ", None), ("the docs", "https://example.com/a(b)"), (" today", None)):
        r = p2.add_run()
        r.text = text
        if url:
            set_run_link(r, url)
    p3 = box.text_frame.add_paragraph()
    r = p3.add_run()
    r.text = "Dangerous link"
    set_run_link(r, "javascript:alert(1)")

    box2 = add_textbox(s2, 1, 1, 8, 1.5)
    p = box2.text_frame.paragraphs[0]
    run = p.add_run()
    run.text = "Jump to last slide"
    set_run_internal_jump(run, s2, s3)

    box3 = add_textbox(s3, 1, 1, 8, 2)
    box3.click_action.hyperlink.address = "https://example.com/cta"
    box3.text_frame.paragraphs[0].text = "Shape click action external"

    box4 = add_textbox(s3, 1, 3, 8, 1.5)
    box4.click_action.target_slide = s1
    box4.text_frame.paragraphs[0].text = "Shape click action jump home"

    prs.save(path)


def add_chart_slide(prs, chart_type, title, categories, series):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    data = CategoryChartData()
    data.categories = categories
    for name, values in series:
        data.add_series(name, values)
    frame = slide.shapes.add_chart(
        chart_type, Inches(1), Inches(1.2), Inches(8), Inches(4.5), data
    )
    frame.chart.has_title = True
    frame.chart.chart_title.text_frame.text = title
    return slide


def build_charts(path: Path) -> None:
    from pptx.enum.chart import XL_CHART_TYPE

    prs = Presentation()
    title_slide = prs.slides.add_slide(prs.slide_layouts[6])
    title_slide.shapes.add_textbox(
        Inches(1), Inches(0.3), Inches(8), Inches(0.8)
    ).text_frame.paragraphs[0].text = "Chart corpus"

    add_chart_slide(
        prs, XL_CHART_TYPE.COLUMN_CLUSTERED, "Quarterly revenue",
        ["Q1", "Q2", "Q3"],
        [("North", (10, 20.5, 30)), ("South", (15, 25, 35))],
    )
    add_chart_slide(
        prs, XL_CHART_TYPE.LINE_MARKERS, "Traffic trend",
        ["Mon", "Tue", "Wed"],
        [("Visits", (100, 250, 175))],
    )
    add_chart_slide(
        prs, XL_CHART_TYPE.PIE, "Market share",
        ["A", "B", "C"],
        [("Share", (40, 35, 25))],
    )
    prs.save(path)


def build_tables_images(path: Path) -> None:
    from PIL import Image
    import io

    prs = Presentation()
    blank = prs.slide_layouts[6]

    buffer = io.BytesIO()
    Image.new("RGB", (64, 32), (40, 120, 200)).save(buffer, format="PNG")
    png_bytes = buffer.getvalue()

    s1 = prs.slides.add_slide(blank)
    s1.shapes.add_picture(io.BytesIO(png_bytes), Inches(1), Inches(1), Inches(2), Inches(1))
    rows, cols = 3, 2
    table = s1.shapes.add_table(rows, cols, Inches(4), Inches(1), Inches(4), Inches(2)).table
    table.cell(0, 0).text = "Name"
    table.cell(0, 1).text = "Value|Escaped"
    table.cell(1, 0).text = "alpha"
    table.cell(1, 1).text = "1"
    table.cell(2, 0).text = "beta beta"
    table.cell(2, 1).text = "2"
    notes = s1.notes_slide.notes_text_frame
    notes.text = "Speaker note for slide one."

    s2 = prs.slides.add_slide(blank)
    s2.shapes.add_picture(io.BytesIO(png_bytes), Inches(1), Inches(1), Inches(3), Inches(1.5))
    box = add_textbox(s2, 1, 3.5, 8, 2)
    frame = box.text_frame
    frame.paragraphs[0].text = "First paragraph"
    frame.add_paragraph().text = "Second paragraph"

    prs.save(path)


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    build_hyperlinks(OUTPUT_DIR / "hyperlinks.pptx")
    build_charts(OUTPUT_DIR / "charts.pptx")
    build_tables_images(OUTPUT_DIR / "tables-images.pptx")
    print(f"Fixtures written to {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
