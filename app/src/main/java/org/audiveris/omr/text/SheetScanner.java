//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                     S h e e t S c a n n e r                                    //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2026. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.text;

import org.audiveris.omr.OMR;
import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.glyph.GlyphFactory;
import org.audiveris.omr.image.ImageUtil;
import org.audiveris.omr.image.Template;
import org.audiveris.omr.run.Orientation;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.run.RunTableFactory;
import org.audiveris.omr.image.PixelSource;
import org.audiveris.omr.sheet.PageCleaner;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.StaffManager;
import org.audiveris.omr.sheet.SystemInfo;
import org.audiveris.omr.sheet.ui.ImageView;
import org.audiveris.omr.sheet.ui.PixelBoard;
import org.audiveris.omr.sheet.ui.ScrollImageView;
import org.audiveris.omr.sig.SIGraph;
import org.audiveris.omr.sig.inter.HeadInter;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.LedgerInter;
import org.audiveris.omr.ui.BoardsPane;
import org.audiveris.omr.util.StopWatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ij.process.ByteProcessor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Class <code>SheetScanner</code> runs OCR on the whole sheet, where good inters and
 * staves core areas have been blanked.
 * <p>
 * Tesseract is used in MULTI_BLOCK layout mode, meaning that the sheet may contain several blocks
 * of text.
 * <p>
 * The raw OCR output will later be processed at system level by dedicated TextBuilder instances.
 *
 * @author Hervé Bitteur
 */
public class SheetScanner
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(SheetScanner.class);

    //~ Instance fields ----------------------------------------------------------------------------

    /** Related sheet. */
    private final Sheet sheet;

    /** Buffer used by OCR. */
    private ByteProcessor buffer;

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new <code>TextPageScanner</code> object.
     *
     * @param sheet the sheet to process
     */
    public SheetScanner (Sheet sheet)
    {
        this.sheet = sheet;
    }

    //~ Methods ------------------------------------------------------------------------------------

    //-----------//
    // getBuffer //
    //-----------//
    /**
     * @return the buffer
     */
    public ByteProcessor getBuffer ()
    {
        return buffer;
    }

    //---------------//
    // getCleanImage //
    //---------------//
    private BufferedImage getCleanImage ()
    {
        Picture picture = sheet.getPicture();
        ByteProcessor buf = picture.getSource(Picture.SourceKey.NO_STAFF);

        BufferedImage img = buf.getBufferedImage();
        buffer = new ByteProcessor(img);

        TextsCleaner cleaner = new TextsCleaner(buffer, img.createGraphics(), sheet);
        cleaner.eraseInters();

        // Display for visual check?
        if (constants.displayTexts.isSet() && (OMR.gui != null)) {
            sheet.getStub().getAssembly().addViewTab(
                    "Texts",
                    new ScrollImageView(sheet, new ImageView(img)
                    {
                        @Override
                        protected void renderItems (Graphics2D g)
                        {
                            sheet.renderItems(g); // Apply registered sheet renderers
                        }
                    }),
                    new BoardsPane(new PixelBoard(sheet)));
        }

        // Save a copy on disk?
        if (constants.saveTextsBuffer.isSet()) {
            ImageUtil.saveOnDisk(img, sheet.getId(), "text");
        }

        return img;
    }

    //-----------//
    // scanSheet //
    //-----------//
    /**
     * Get a clean image of whole sheet and run OCR on it.
     *
     * @return the list of OCR'd lines found and filtered
     */
    public List<TextLine> scanSheet ()
    {
        final StopWatch watch = new StopWatch("scanSheet");

        try {
            // Get clean sheet image
            watch.start("getCleanImage");
            final BufferedImage image = getCleanImage(); // This also sets buffer member

            // Perform OCR on whole image
            final String languages = sheet.getStub().getOcrLanguages();
            logger.debug("scanSheet lan:{} on {}", languages, sheet);
            watch.start("OCR recognize");

            final List<TextLine> lines = OcrUtil.scan(
                    image,
                    OCR.LayoutMode.MULTI_BLOCK,
                    languages,
                    sheet,
                    sheet.getId());

            watch.start("rescanCrossedLines");

            return rescanCrossedLines(image, lines, languages);
        } finally {
            if (constants.printWatch.isSet()) {
                watch.print();
            }
        }
    }

    //--------------//
    // connectedInk //
    //--------------//
    /**
     * Report the ink within the provided box, and all the ink connected to it.
     *
     * @param raster the image raster
     * @param box    the box to start from
     * @return the foreground pixels found
     */
    private static List<Point> connectedInk (WritableRaster raster,
                                             Rectangle box)
    {
        final int width = raster.getWidth();
        final Rectangle frame = new Rectangle(0, 0, width, raster.getHeight());
        final Rectangle seeds = box.intersection(frame);
        final boolean[] visited = new boolean[width * raster.getHeight()];
        final Deque<Point> stack = new ArrayDeque<>();
        final List<Point> ink = new ArrayList<>();

        for (int y = seeds.y; y < (seeds.y + seeds.height); y++) {
            for (int x = seeds.x; x < (seeds.x + seeds.width); x++) {
                stack.push(new Point(x, y));
            }
        }

        while (!stack.isEmpty()) {
            final Point p = stack.pop();

            if (!frame.contains(p) || visited[(p.y * width) + p.x]) {
                continue;
            }

            visited[(p.y * width) + p.x] = true;

            if (raster.getSample(p.x, p.y, 0) != PixelSource.FOREGROUND) {
                continue;
            }

            ink.add(p);

            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx != 0) || (dy != 0)) {
                        stack.push(new Point(p.x + dx, p.y + dy));
                    }
                }
            }
        }

        return ink;
    }

    //-----------------//
    // isLetterOrDigit //
    //-----------------//
    private static boolean isLetterOrDigit (TextChar ch)
    {
        final String value = ch.getValue();

        return !value.isEmpty() && value.codePoints().allMatch(Character::isLetterOrDigit);
    }

    //--------//
    // isSign //
    //--------//
    /**
     * Tell whether the provided ink is a sign drawn across a text line, not text.
     * <p>
     * A coda or a segno beside its label is broad, and centered on the label but taller.
     *
     * @param ink  the connected ink of a character that overhangs the letters above and below
     * @param band the band of the letters
     * @return true for a sign
     */
    private static boolean isSign (List<Point> ink,
                                   Band band)
    {
        if (ink.isEmpty()) {
            return false;
        }

        final Rectangle box = new Rectangle(ink.get(0));
        ink.forEach(p -> box.add(new Rectangle(p.x, p.y, 1, 1)));

        return (box.height >= (constants.minSignHeightRatio.getValue() * band.height()))
                && (box.width >= (constants.minSignWidthRatio.getValue() * band.height()));
    }

    //------------//
    // letterBand //
    //------------//
    /**
     * Report the band spanned by the letters of a line.
     * <p>
     * Its letters and digits count, except one more than
     * {@link Constants#maxLetterHeightRatio} times as tall as their median, which is no letter.
     *
     * @param line the OCR'd line
     * @return the band, or null if the line has no letter
     */
    private static Band letterBand (TextLine line)
    {
        final List<Rectangle> letters = new ArrayList<>();

        for (TextChar ch : line.getChars()) {
            if (isLetterOrDigit(ch)) {
                letters.add(ch.getBounds());
            }
        }

        if (letters.isEmpty()) {
            return null;
        }

        final List<Integer> heights = new ArrayList<>();
        letters.forEach(box -> heights.add(box.height));
        Collections.sort(heights);

        final double maxHeight = constants.maxLetterHeightRatio.getValue() //
                * heights.get(heights.size() / 2);
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;

        for (Rectangle box : letters) {
            if (box.height <= maxHeight) {
                top = Math.min(top, box.y);
                bottom = Math.max(bottom, box.y + box.height);
            }
        }

        return new Band(top, bottom);
    }

    //--------------------//
    // rescanCrossedLines //
    //--------------------//
    /**
     * Read again, without its signs, every line that a sign is drawn across.
     * <p>
     * The OCR takes a coda drawn beside "Coda" as a character of that word, so the symbol
     * step never sees its ink, and the size of the sign misleads the reading of the line.
     * Such a line is read again from the ink of its own words, the ink of its signs erased.
     *
     * @param image     the clean sheet image
     * @param lines     the lines OCR'd on the whole image
     * @param languages the OCR languages
     * @return the lines, each one crossed by a sign replaced by its new reading
     */
    private List<TextLine> rescanCrossedLines (BufferedImage image,
                                               List<TextLine> lines,
                                               String languages)
    {
        final List<TextLine> result = new ArrayList<>();

        for (TextLine line : lines) {
            final Band band = letterBand(line);

            if (band == null) {
                result.add(line);
                continue;
            }

            final Rectangle area = line.getBounds();
            final BufferedImage crop = wordsInk(image, line, area);
            final WritableRaster raster = crop.getRaster();
            boolean crossed = false;

            for (TextChar ch : line.getChars()) {
                final Rectangle box = ch.getBounds();

                if ((box.y < band.top()) && ((box.y + box.height) > band.bottom())) {
                    box.translate(-area.x, -area.y);
                    final List<Point> ink = connectedInk(raster, box);

                    if (isSign(ink, band)) {
                        ink.forEach(p -> raster.setSample(p.x, p.y, 0, PixelSource.BACKGROUND));
                        crossed = true;
                    }
                }
            }

            if (!crossed) {
                result.add(line);
                continue;
            }

            final List<TextLine> reread = OcrUtil.scan(
                    crop,
                    OCR.LayoutMode.SINGLE_BLOCK,
                    languages,
                    sheet,
                    sheet.getId() + "/crossed-" + area.y);
            reread.forEach(l -> l.translate(area.x, area.y));
            logger.info(
                    "Line \"{}\" crossed by a sign, read again as {}",
                    line.getValue(),
                    reread.stream().map(TextLine::getValue).toList());
            result.addAll(reread);
        }

        return result;
    }

    //----------//
    // wordsInk //
    //----------//
    /**
     * Copy the ink of the words of a line, and nothing else, onto an image of the line area.
     *
     * @param image the clean sheet image
     * @param line  the line
     * @param area  the line area
     * @return the image of the words ink, relative to area
     */
    private static BufferedImage wordsInk (BufferedImage image,
                                           TextLine line,
                                           Rectangle area)
    {
        final BufferedImage crop = new BufferedImage(
                area.width,
                area.height,
                BufferedImage.TYPE_BYTE_GRAY);
        final Graphics2D g = crop.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, area.width, area.height);

        for (TextWord word : line.getWords()) {
            final Rectangle box = word.getBounds();
            g.drawImage(
                    image.getSubimage(box.x, box.y, box.width, box.height),
                    box.x - area.x,
                    box.y - area.y,
                    null);
        }

        g.dispose();

        return crop;
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //------//
    // Band //
    //------//
    /** The ordinates spanned by the letters of a line. */
    private record Band(int top, int bottom)
    {
        int height ()
        {
            return bottom - top;
        }
    }

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Constant.Ratio maxLetterHeightRatio = new Constant.Ratio(
                2.0,
                "Maximum height of a letter relative to the median letter of its line");

        private final Constant.Ratio minSignHeightRatio = new Constant.Ratio(
                1.5,
                "Minimum height of a sign drawn across a text line, relative to its letters");

        private final Constant.Ratio minSignWidthRatio = new Constant.Ratio(
                1.0,
                "Minimum width of a sign drawn across a text line, relative to its letters height");

        private final Constant.Boolean printWatch = new Constant.Boolean(
                false,
                "Should we print out the stop watch?");

        private final Constant.Boolean displayTexts = new Constant.Boolean(
                false,
                "Should we display the texts image?");

        private final Constant.Boolean saveTextsBuffer = new Constant.Boolean(
                false,
                "Should we save texts buffer on disk?");

        private final Scale.Fraction staffHorizontalMargin = new Scale.Fraction(
                0.25,
                "Horizontal margin around staff core area");

        private final Scale.Fraction staffVerticalMargin = new Scale.Fraction(
                0.25,
                "Vertical margin around staff core area");
    }

    //--------------//
    // TextsCleaner //
    //--------------//
    /**
     * Class <code>TextsCleaner</code> erases shapes to prepare texts retrieval.
     */
    private static class TextsCleaner
            extends PageCleaner
    {
        /** Scale-dependent parameters. */
        private final Parameters params;

        /**
         * Creates a new <code>TextsCleaner</code> object.
         *
         * @param buffer page buffer
         * @param g      graphics context on buffer
         * @param sheet  related sheet
         */
        TextsCleaner (ByteProcessor buffer,
                      Graphics2D g,
                      Sheet sheet)
        {
            super(buffer, g, sheet);
            params = new Parameters(sheet.getScale());
        }

        //-------------------//
        // eraseBorderGlyphs //
        //-------------------//
        /**
         * Erase from text image the glyphs that intersect or touch a staff core area.
         * <p>
         * (We also tried to remove too small glyphs, but this led to poor recognition by OCR)
         *
         * @param glyphs all the glyph instances in image
         * @param cores  all staves cores
         */
        private void eraseBorderGlyphs (List<Glyph> glyphs,
                                        List<Area> cores)
        {
            for (Glyph glyph : glyphs) {
                // Check position WRT staves cores
                Rectangle glyphBox = glyph.getBounds();
                glyphBox.grow(1, 1); // To catch touching glyphs (on top of intersecting ones)

                for (Area core : cores) {
                    if (core.intersects(glyphBox)) {
                        glyph.getRunTable().render(g, glyph.getTopLeft());

                        break;
                    }
                }
            }
        }

        //-------------//
        // eraseInters //
        //-------------//
        /**
         * Erase from image graphics all instances of good inter instances.
         */
        public void eraseInters ()
        {
            List<Area> cores = new ArrayList<>();

            for (SystemInfo system : sheet.getSystems()) {
                final SIGraph sig = system.getSig();
                final List<Inter> erased = new ArrayList<>();

                for (Inter inter : sig.vertexSet()) {
                    if (!inter.isRemoved()) {
                        if (canHide(inter)) {
                            erased.add(inter);
                        }
                    }
                }

                // Erase the inters
                for (Inter inter : erased) {
                    inter.accept(this);
                }

                // Erase the core area of each staff
                for (Staff staff : system.getStaves()) {
                    Area core = StaffManager.getCoreArea(staff, params.hMargin, params.vMargin);
                    cores.add(core);
                    ///staff.addAttachment("core", core); // Just for visual check
                    g.fill(core);
                }
            }

            // Binarize
            buffer.threshold(127);

            // Build all glyphs out of buffer and erase those that intersect a staff core area
            RunTable table = new RunTableFactory(Orientation.VERTICAL).createTable(buffer);
            List<Glyph> glyphs = GlyphFactory.buildGlyphs(table, null);
            eraseBorderGlyphs(glyphs, cores);
        }

        //-------//
        // visit //
        //-------//
        @Override
        public void visit (HeadInter inter)
        {
            final Template template = inter.getTemplate();
            final Rectangle tplBox = template.getBounds(inter.getBounds());

            // Use underlying glyph (enlarged)
            final List<Point> fores = template.getForegroundPixels(tplBox, buffer, true);

            // Erase foreground pixels
            for (final Point p : fores) {
                g.fillRect(tplBox.x + p.x, tplBox.y + p.y, 1, 1);
            }
        }

        //-------//
        // visit //
        //-------//
        @Override
        public void visit (LedgerInter ledger)
        {
            // Thicken the ledgerline 1 pixel above & 1 pixel below
            final Stroke oldStroke = g.getStroke();
            float thickness = (float) ledger.getThickness();
            thickness += 2;
            g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.draw(ledger.getMedian());
            g.setStroke(oldStroke);
        }

        //------------//
        // Parameters //
        //------------//
        private static class Parameters
        {
            final int hMargin;

            final int vMargin;

            Parameters (Scale scale)
            {
                hMargin = scale.toPixels(constants.staffHorizontalMargin);
                vMargin = scale.toPixels(constants.staffVerticalMargin);
            }
        }
    }
}
