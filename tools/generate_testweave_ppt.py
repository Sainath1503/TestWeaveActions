from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import html


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "TestWeave_Features_Roadmap.pptx"
LOGO = ROOT / "src" / "main" / "resources" / "testweave-logo.png"

SLIDE_W = 13_333_333
SLIDE_H = 7_500_000

BG = "030817"
PANEL = "0A1028"
PANEL_2 = "101735"
CYAN = "18D7E6"
BLUE = "1B7DFF"
PURPLE = "8D4CFF"
MINT = "20E6C3"
WHITE = "FFFFFF"
MUTED = "B9C6E8"
YELLOW = "F3CE60"


def esc(text):
    return html.escape(str(text), quote=True)


def emu(value):
    return int(value)


def solid(color):
    return f'<a:solidFill><a:srgbClr val="{color}"/></a:solidFill>'


def alpha_solid(color, alpha):
    return f'<a:solidFill><a:srgbClr val="{color}"><a:alpha val="{alpha}"/></a:srgbClr></a:solidFill>'


def line(color=CYAN, width=12700, alpha=None):
    fill = alpha_solid(color, alpha) if alpha else solid(color)
    return f'<a:ln w="{width}">{fill}</a:ln>'


def no_line():
    return '<a:ln><a:noFill/></a:ln>'


def shape_xml(idx, x, y, w, h, fill, outline=None, radius=True):
    prst = "roundRect" if radius else "rect"
    outline_xml = outline if outline is not None else no_line()
    return f"""
      <p:sp>
        <p:nvSpPr><p:cNvPr id="{idx}" name="Shape {idx}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
        <p:spPr>
          <a:xfrm><a:off x="{emu(x)}" y="{emu(y)}"/><a:ext cx="{emu(w)}" cy="{emu(h)}"/></a:xfrm>
          <a:prstGeom prst="{prst}"><a:avLst/></a:prstGeom>
          {fill}
          {outline_xml}
        </p:spPr>
        <p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody>
      </p:sp>
    """


def text_xml(idx, x, y, w, h, text, size=2800, color=WHITE, bold=False,
             align="l", font="Aptos", fill=None, outline=None, margin=True):
    weight = ' b="1"' if bold else ""
    fill_xml = fill if fill else '<a:noFill/>'
    outline_xml = outline if outline is not None else no_line()
    inset = 'lIns="91440" tIns="45720" rIns="91440" bIns="45720"' if margin else 'lIns="0" tIns="0" rIns="0" bIns="0"'
    lines = str(text).split("\n")
    paragraphs = []
    for line_text in lines:
        paragraphs.append(f"""
          <a:p>
            <a:pPr algn="{align}"/>
            <a:r>
              <a:rPr lang="en-US" sz="{size}"{weight}>
                <a:solidFill><a:srgbClr val="{color}"/></a:solidFill>
                <a:latin typeface="{font}"/>
              </a:rPr>
              <a:t>{esc(line_text)}</a:t>
            </a:r>
          </a:p>
        """)
    return f"""
      <p:sp>
        <p:nvSpPr><p:cNvPr id="{idx}" name="Text {idx}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
        <p:spPr>
          <a:xfrm><a:off x="{emu(x)}" y="{emu(y)}"/><a:ext cx="{emu(w)}" cy="{emu(h)}"/></a:xfrm>
          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
          {fill_xml}
          {outline_xml}
        </p:spPr>
        <p:txBody>
          <a:bodyPr wrap="square" {inset}/>
          <a:lstStyle/>
          {''.join(paragraphs)}
        </p:txBody>
      </p:sp>
    """


def picture_xml(idx, rel_id, x, y, w, h, name="TestWeave Logo"):
    return f"""
      <p:pic>
        <p:nvPicPr><p:cNvPr id="{idx}" name="{esc(name)}"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
        <p:blipFill><a:blip r:embed="{rel_id}"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
        <p:spPr>
          <a:xfrm><a:off x="{emu(x)}" y="{emu(y)}"/><a:ext cx="{emu(w)}" cy="{emu(h)}"/></a:xfrm>
          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
        </p:spPr>
      </p:pic>
    """


def bullet_xml(idx, x, y, w, h, title, bullets, accent=CYAN):
    body = [
        text_xml(idx, x, y, w, 470000, title, 2500, accent, True, fill=alpha_solid(PANEL_2, 76000), outline=line(accent, 9000, 65000))
    ]
    yy = y + 560000
    for i, item in enumerate(bullets):
        body.append(shape_xml(idx + i + 100, x, yy + i * 520000, 115000, 115000, solid(accent), None, True))
        body.append(text_xml(idx + i + 200, x + 175000, yy + i * 520000 - 60000, w - 175000, 250000, item, 1600, WHITE, False, fill=None, margin=False))
    return "\n".join(body)


def footer(idx, slide_no):
    return (
        shape_xml(idx, 420000, 7_150_000, 3_100_000, 18000, solid(BLUE), None, False)
        + shape_xml(idx + 1, 3_520_000, 7_150_000, 2_100_000, 18000, solid(PURPLE), None, False)
        + shape_xml(idx + 2, 5_620_000, 7_150_000, 2_900_000, 18000, solid(CYAN), None, False)
        + text_xml(idx + 3, 10_700_000, 6_930_000, 1_900_000, 250000, f"TestWeave  |  {slide_no:02d}", 1100, MUTED, False, "r", margin=False)
    )


def slide_xml(content):
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
       xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
       xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:bg><p:bgPr>{solid(BG)}<a:effectLst/></p:bgPr></p:bg>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
      {content}
    </p:spTree>
  </p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sld>"""


def title_band(title, subtitle, slide_no, logo=False):
    parts = [
        text_xml(10, 520000, 300000, 7_900_000, 560000, title, 3300, WHITE, True, margin=False),
        text_xml(11, 540000, 870000, 8_400_000, 320000, subtitle, 1450, MUTED, False, margin=False),
        footer(900, slide_no),
    ]
    if logo:
        parts.append(picture_xml(12, "rId2", 10_850_000, 230000, 1_260_000, 1_260_000))
    return "\n".join(parts)


def build_slides():
    slides = []

    slides.append(slide_xml(
        shape_xml(2, 0, 0, SLIDE_W, SLIDE_H, alpha_solid("000000", 32000), None, False)
        + picture_xml(3, "rId2", 7_580_000, 490000, 4_650_000, 4_650_000)
        + text_xml(4, 620000, 1_340_000, 6_600_000, 820000, "TestWeave", 5600, WHITE, True, margin=False)
        + text_xml(5, 650000, 2_150_000, 6_450_000, 460000, "Weave. Validate. Deliver.", 2100, CYAN, True, margin=False)
        + text_xml(6, 650000, 3_030_000, 6_400_000, 980000, "A unified desktop validation workbench for API, JSON, DB, performance, and web test flows.", 2300, MUTED, False, margin=False)
        + shape_xml(7, 660000, 4_570_000, 1_350_000, 72000, solid(BLUE), None, False)
        + shape_xml(8, 2_020_000, 4_570_000, 1_350_000, 72000, solid(PURPLE), None, False)
        + shape_xml(9, 3_380_000, 4_570_000, 1_350_000, 72000, solid(CYAN), None, False)
        + text_xml(10, 650000, 5_050_000, 6_500_000, 430000, "Product Feature Overview & Future Roadmap", 1800, WHITE, False, margin=False)
        + footer(900, 1)
    ))

    slides.append(slide_xml(
        title_band("The Problem TestWeave Solves", "Validation work is scattered across tools, logs, scripts, and browser sessions.", 2, True)
        + bullet_xml(20, 620000, 1_550_000, 3_750_000, 3_900_000, "Today’s Friction", [
            "API checks live separately from DB checks",
            "UI flows are hard to record after login or MFA",
            "Performance evidence is not tied to functional validation",
            "Reusable values are copied manually across tools"
        ], BLUE)
        + bullet_xml(40, 4_800_000, 1_550_000, 3_750_000, 3_900_000, "TestWeave Response", [
            "One workbench for API, DB, UI, JSON, and load tests",
            "Capture variables once and reuse everywhere",
            "Record web flows from fresh or active browsers",
            "Store validation evidence for release decisions"
        ], PURPLE)
        + bullet_xml(60, 8_980_000, 1_550_000, 3_750_000, 3_900_000, "Outcome", [
            "Less tool switching",
            "Faster root-cause isolation",
            "Repeatable regression packs",
            "Stronger confidence before delivery"
        ], CYAN)
    ))

    slides.append(slide_xml(
        title_band("Current Feature Landscape", "A single workspace for the validations teams already perform every day.", 3, True)
        + bullet_xml(20, 720000, 1_500_000, 3_600_000, 3_800_000, "API & JSON", [
            "REST calls with headers, token auth, and payload editing",
            "Pretty/raw response inspection",
            "Strict or lenient JSON comparisons",
            "Response variable capture from JSON paths"
        ], BLUE)
        + bullet_xml(50, 4_860_000, 1_500_000, 3_600_000, 3_800_000, "DB & Performance", [
            "JDBC connection profiles",
            "Parameterized SQL with saved variables",
            "Resultset preview and DB-to-API validation",
            "Load-test metrics and report access"
        ], PURPLE)
        + bullet_xml(80, 9_000_000, 1_500_000, 3_600_000, 3_800_000, "Web Testing", [
            "Browser-side step recorder",
            "Attach to debug Chrome for complex flows",
            "Replay captured actions and assertions",
            "Screenshot and validation steps"
        ], CYAN)
    ))

    slides.append(slide_xml(
        title_band("API Validation & Variable Capture", "Turn one successful API call into reusable test data.", 4, True)
        + shape_xml(20, 700000, 1_550_000, 5_560_000, 4_350_000, alpha_solid(PANEL, 92000), line(BLUE, 13000, 70000))
        + text_xml(21, 1_040_000, 1_900_000, 4_900_000, 500000, "Request Builder", 2800, BLUE, True, margin=False)
        + text_xml(22, 1_040_000, 2_550_000, 4_850_000, 1_700_000, "Configure method, endpoint, auth, headers, and body in one compact panel. Send the request and inspect status, latency, size, headers, cookies, and formatted response payload.", 1900, WHITE)
        + shape_xml(30, 7_050_000, 1_550_000, 5_560_000, 4_350_000, alpha_solid(PANEL, 92000), line(CYAN, 13000, 70000))
        + text_xml(31, 7_390_000, 1_900_000, 4_900_000, 500000, "Variables That Travel", 2800, CYAN, True, margin=False)
        + text_xml(32, 7_390_000, 2_550_000, 4_850_000, 1_700_000, "Capture JSON fields as named variables and reuse them in API URLs, request bodies, DB SQL, web steps, and performance tests. This creates a living chain across validations.", 1900, WHITE)
        + footer(900, 4)
    ))

    slides.append(slide_xml(
        title_band("DB Validator: From Query to Evidence", "Validate API truth against database state without leaving the tool.", 5, True)
        + bullet_xml(20, 700000, 1_500_000, 3_900_000, 4_000_000, "Connection & Query", [
            "Save and load JDBC connection profiles",
            "Insert variables directly into SQL templates",
            "Preview query resultsets in a table",
            "Load DB columns into validation rules"
        ], BLUE)
        + bullet_xml(50, 4_900_000, 1_500_000, 3_900_000, 4_000_000, "Validation Rules", [
            "Compare API fields to DB columns",
            "Support equality, contains, and ordered comparisons",
            "Handle multi-row results and row-specific columns",
            "Display pass/fail details per rule"
        ], PURPLE)
        + bullet_xml(80, 9_100_000, 1_500_000, 3_300_000, 4_000_000, "Variable Export", [
            "Select any resultset cell",
            "Save row/column values as variables",
            "Reuse DB values in later test stages"
        ], MINT)
    ))

    slides.append(slide_xml(
        title_band("Web Testing: Complex Flow Capture", "Record browser behavior from fresh sessions or already-open debug Chrome windows.", 6, True)
        + shape_xml(20, 670000, 1_500_000, 3_700_000, 3_900_000, alpha_solid(PANEL, 92000), line(BLUE, 12000, 65000))
        + text_xml(21, 960000, 1_820_000, 3_050_000, 420000, "Fresh Browser", 2600, BLUE, True, "c", margin=False)
        + text_xml(22, 930000, 2_520_000, 3_150_000, 1_750_000, "Launch a Playwright-controlled browser and record clicks, typing, navigation, assertions, and screenshots for clean reproducible tests.", 1750, WHITE)
        + shape_xml(30, 4_830_000, 1_500_000, 3_700_000, 3_900_000, alpha_solid(PANEL, 92000), line(PURPLE, 12000, 65000))
        + text_xml(31, 5_120_000, 1_820_000, 3_050_000, 420000, "Attach Browser", 2600, PURPLE, True, "c", margin=False)
        + text_xml(32, 5_090_000, 2_520_000, 3_150_000, 1_750_000, "Connect to debug Chrome on port 9222 to capture flows after manual login, SSO, MFA, deep navigation, or hard-to-script setup.", 1750, WHITE)
        + shape_xml(40, 8_990_000, 1_500_000, 3_700_000, 3_900_000, alpha_solid(PANEL, 92000), line(CYAN, 12000, 65000))
        + text_xml(41, 9_280_000, 1_820_000, 3_050_000, 420000, "Smart Replay", 2600, CYAN, True, "c", margin=False)
        + text_xml(42, 9_250_000, 2_520_000, 3_150_000, 1_750_000, "Replay native dropdowns with selectOption, resolve variables in steps, and surface pass/fail evidence for each browser action.", 1750, WHITE)
        + footer(900, 6)
    ))

    slides.append(slide_xml(
        title_band("Test Suite Runner Vision", "The canvas where individual validations become orchestrated quality journeys.", 7, True)
        + shape_xml(20, 760000, 1_520_000, 11_820_000, 3_950_000, alpha_solid(PANEL, 90000), line(CYAN, 15000, 62000))
        + text_xml(21, 1_060_000, 1_900_000, 10_900_000, 650000, "Drag, connect, run, and review", 3400, WHITE, True, "c", margin=False)
        + text_xml(22, 1_500_000, 2_780_000, 10_000_000, 1_320_000, "Future Test Suite Runner will let users arrange API, DB, web, performance, and comparison test cases on a canvas. Variables can flow from one node to another, creating clear end-to-end test stories.", 2100, MUTED, False, "c")
        + text_xml(23, 1_300_000, 4_560_000, 10_400_000, 440000, "API response -> DB validation -> UI flow -> Performance check -> Release evidence", 1850, CYAN, True, "c", margin=False)
        + footer(900, 7)
    ))

    slides.append(slide_xml(
        title_band("Unified Dashboard: One Quality Command Center", "A consolidated dashboard makes validation status visible, comparable, and actionable.", 8, True)
        + bullet_xml(20, 760000, 1_500_000, 3_700_000, 4_200_000, "Why It Helps", [
            "Single pass/fail view across all validation types",
            "Faster release readiness decisions",
            "Trend visibility across builds and environments",
            "Less manual status consolidation"
        ], BLUE)
        + bullet_xml(50, 4_820_000, 1_500_000, 3_700_000, 4_200_000, "What It Shows", [
            "API, DB, UI, JSON, and performance health",
            "Flaky tests and failing components",
            "Latest evidence, screenshots, and reports",
            "Environment-wise validation history"
        ], PURPLE)
        + bullet_xml(80, 8_880_000, 1_500_000, 3_700_000, 4_200_000, "Business Value", [
            "Shared QA and dev understanding",
            "Clear audit trail for releases",
            "Earlier defect discovery",
            "Better prioritization of fixes"
        ], CYAN)
    ))

    slides.append(slide_xml(
        title_band("n8n + Jira Integration", "Automation can turn failed validations into trackable engineering work.", 9, True)
        + shape_xml(20, 700000, 1_520_000, 11_900_000, 4_450_000, alpha_solid(PANEL, 92000), line(PURPLE, 15000, 65000))
        + text_xml(21, 1_030_000, 1_880_000, 10_900_000, 450000, "How the workflow can operate", 2800, PURPLE, True, margin=False)
        + text_xml(22, 1_050_000, 2_580_000, 10_750_000, 2_080_000,
                   "1. TestWeave completes a validation run and exports structured results.\n2. n8n watches the output, webhook, or scheduled report location.\n3. Failed critical validations create or update Jira issues with logs, payloads, screenshots, SQL evidence, and environment tags.\n4. Jira status can flow back into a dashboard so teams see whether failures are accepted, in progress, or fixed.",
                   1700, WHITE)
        + text_xml(23, 1_050_000, 5_000_000, 10_750_000, 430000,
                   "Result: fewer missed defects, cleaner handoff, and a tighter validation-to-remediation loop.",
                   1900, CYAN, True, "c", margin=False)
        + footer(900, 9)
    ))

    slides.append(slide_xml(
        title_band("Swing to JavaFX: UI Evolution", "JavaFX can help TestWeave feel more modern, visual, and workflow-oriented.", 10, True)
        + bullet_xml(20, 760000, 1_500_000, 3_700_000, 4_100_000, "Why Move", [
            "Cleaner layouts and responsive controls",
            "Modern styling with CSS",
            "Better charts, dashboards, and canvas interactions",
            "Smoother future UX for suite orchestration"
        ], BLUE)
        + bullet_xml(50, 4_820_000, 1_500_000, 3_700_000, 4_100_000, "Migration Approach", [
            "Keep services and models reusable",
            "Move screen-by-screen instead of rewriting all logic",
            "Start with dashboard and Test Suite Runner",
            "Retain existing Swing features during transition"
        ], PURPLE)
        + bullet_xml(80, 8_880_000, 1_500_000, 3_700_000, 4_100_000, "UX Gains", [
            "Drag/drop suite canvas",
            "Richer tables and filters",
            "Interactive charts and timelines",
            "More polished product identity"
        ], CYAN)
    ))

    slides.append(slide_xml(
        title_band("Future Roadmap", "A practical path from tool to validation platform.", 11, True)
        + bullet_xml(20, 760000, 1_500_000, 3_700_000, 4_300_000, "Now", [
            "Stabilize web recording and replay",
            "Polish DB resultset variable capture",
            "Package repeatable demo workflows",
            "Improve reporting exports"
        ], BLUE)
        + bullet_xml(50, 4_820_000, 1_500_000, 3_700_000, 4_300_000, "Next", [
            "Test Suite Runner drag/drop canvas",
            "Unified dashboard MVP",
            "n8n webhook/report integration",
            "Jira issue automation for failures"
        ], PURPLE)
        + bullet_xml(80, 8_880_000, 1_500_000, 3_700_000, 4_300_000, "Later", [
            "JavaFX migration",
            "CI/CD execution mode",
            "Team-shared test repositories",
            "AI-assisted test generation and healing"
        ], CYAN)
    ))

    slides.append(slide_xml(
        picture_xml(20, "rId2", 860000, 780000, 3_400_000, 3_400_000)
        + text_xml(30, 4_880_000, 1_250_000, 7_600_000, 700000, "TestWeave", 5200, WHITE, True, margin=False)
        + text_xml(31, 4_900_000, 2_080_000, 7_500_000, 450000, "From scattered validation tasks to one connected quality fabric.", 2200, CYAN, True, margin=False)
        + text_xml(32, 4_920_000, 3_120_000, 7_200_000, 1_420_000, "The roadmap moves TestWeave beyond a desktop helper into a release-confidence platform: orchestrated suites, unified dashboards, automated Jira follow-up, and a modern JavaFX user experience.", 2100, MUTED)
        + shape_xml(40, 4_940_000, 5_250_000, 2_000_000, 72000, solid(BLUE), None, False)
        + shape_xml(41, 6_940_000, 5_250_000, 2_000_000, 72000, solid(PURPLE), None, False)
        + shape_xml(42, 8_940_000, 5_250_000, 2_000_000, 72000, solid(CYAN), None, False)
        + footer(900, 12)
    ))

    return slides


def rels_xml(slides_count):
    rels = [
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>'
    ]
    for i in range(1, slides_count + 1):
        rels.append(f'<Relationship Id="rId{i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide{i}.xml"/>')
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + "".join(rels) + "</Relationships>"


def presentation_xml(slides_count):
    sld_ids = []
    for i in range(1, slides_count + 1):
        sld_ids.append(f'<p:sldId id="{255 + i}" r:id="rId{i + 1}"/>')
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
  <p:sldIdLst>{''.join(sld_ids)}</p:sldIdLst>
  <p:sldSz cx="{SLIDE_W}" cy="{SLIDE_H}" type="wide"/>
  <p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>"""


def content_types_xml(slides_count):
    overrides = [
        '<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>',
        '<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>',
        '<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>',
        '<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>',
    ]
    for i in range(1, slides_count + 1):
        overrides.append(f'<Override PartName="/ppt/slides/slide{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>')
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  {''.join(overrides)}
</Types>"""


MINIMAL_THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="TestWeave">
  <a:themeElements>
    <a:clrScheme name="TestWeave">
      <a:dk1><a:srgbClr val="030817"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>
      <a:dk2><a:srgbClr val="0A1028"/></a:dk2><a:lt2><a:srgbClr val="B9C6E8"/></a:lt2>
      <a:accent1><a:srgbClr val="1B7DFF"/></a:accent1><a:accent2><a:srgbClr val="8D4CFF"/></a:accent2>
      <a:accent3><a:srgbClr val="18D7E6"/></a:accent3><a:accent4><a:srgbClr val="20E6C3"/></a:accent4>
      <a:accent5><a:srgbClr val="F3CE60"/></a:accent5><a:accent6><a:srgbClr val="FFFFFF"/></a:accent6>
      <a:hlink><a:srgbClr val="18D7E6"/></a:hlink><a:folHlink><a:srgbClr val="8D4CFF"/></a:folHlink>
    </a:clrScheme>
    <a:fontScheme name="Aptos"><a:majorFont><a:latin typeface="Aptos Display"/></a:majorFont><a:minorFont><a:latin typeface="Aptos"/></a:minorFont></a:fontScheme>
    <a:fmtScheme name="TestWeave"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>
  </a:themeElements>
</a:theme>"""


SLIDE_LAYOUT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank" preserve="1">
  <p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>"""


SLIDE_MASTER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
  <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
  <p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>
</p:sldMaster>"""


def simple_rels(*items):
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + "".join(items) + "</Relationships>"


def build():
    if not LOGO.exists():
        raise FileNotFoundError(LOGO)
    slides = build_slides()
    with ZipFile(OUT, "w", ZIP_DEFLATED) as pptx:
        pptx.writestr("[Content_Types].xml", content_types_xml(len(slides)))
        pptx.writestr("_rels/.rels", simple_rels('<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>'))
        pptx.writestr("ppt/presentation.xml", presentation_xml(len(slides)))
        pptx.writestr("ppt/_rels/presentation.xml.rels", rels_xml(len(slides)))
        pptx.writestr("ppt/theme/theme1.xml", MINIMAL_THEME)
        pptx.writestr("ppt/slideMasters/slideMaster1.xml", SLIDE_MASTER)
        pptx.writestr("ppt/slideMasters/_rels/slideMaster1.xml.rels", simple_rels(
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>',
            '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>'
        ))
        pptx.writestr("ppt/slideLayouts/slideLayout1.xml", SLIDE_LAYOUT)
        pptx.writestr("ppt/slideLayouts/_rels/slideLayout1.xml.rels", simple_rels(
            '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>'
        ))
        pptx.writestr("ppt/media/testweave-logo.png", LOGO.read_bytes())
        for i, xml in enumerate(slides, 1):
            pptx.writestr(f"ppt/slides/slide{i}.xml", xml)
            pptx.writestr(f"ppt/slides/_rels/slide{i}.xml.rels", simple_rels(
                '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>',
                '<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/testweave-logo.png"/>'
            ))
    print(OUT)


if __name__ == "__main__":
    build()
