//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                 B a s i c T i m e B u i l d e r                                //
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
package org.audiveris.omr.sheet.time;

import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.glyph.Glyph;
import org.audiveris.omr.glyph.GlyphFactory;
import org.audiveris.omr.run.Orientation;
import org.audiveris.omr.run.RunTable;
import org.audiveris.omr.run.RunTableFactory;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.Scale;
import org.audiveris.omr.sheet.Staff;
import org.audiveris.omr.sheet.header.StaffHeader;
import org.audiveris.omr.sheet.rhythm.MeasureStack;
import org.audiveris.omr.sig.inter.Inter;
import org.audiveris.omr.sig.inter.TimeNumberInter;
import org.audiveris.omr.sig.inter.TimeWholeInter;
import org.audiveris.omr.util.HorizontalSide;
import org.audiveris.omr.util.VerticalSide;

import ij.process.Blitter;
import ij.process.ByteProcessor;

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.List;

/**
 * A subclass of TimeBuilder specifically meant for extraction outside system header,
 * further down in the system measures.
 * <p>
 * Symbol extraction has already been performed, so time-signature shaped symbols are now
 * checked for consistency across all staves of the containing system.
 *
 * @author Hervé Bitteur
 */
public class BasicTimeBuilder
        extends TimeBuilder
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    //~ Constructors -------------------------------------------------------------------------------

    /**
     * Creates a new <code>BasicTimeBuilder</code> object.
     *
     * @param staff  underlying staff
     * @param column containing time column
     */
    public BasicTimeBuilder (Staff staff,
                             BasicTimeColumn column)
    {
        super(staff, column);
    }

    //~ Methods ------------------------------------------------------------------------------------

    @Override
    public void cleanup ()
    {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    //----------------//
    // findCandidates //
    //----------------//
    @Override
    protected void findCandidates ()
    {
        // For time symbols found (whole or half), pitch is correct, but abscissa is random
        // For now, at staff level, we can only check that nums & dens are x-compatible
        BasicTimeColumn basicColumn = (BasicTimeColumn) column;
        readOpening(basicColumn);

        for (Inter inter : basicColumn.timeSet) {
            if (inter.getStaff() == staff) {
                if (inter instanceof TimeWholeInter) {
                    wholes.add(inter);
                } else if (inter instanceof TimeNumberInter) {
                    TimeNumberInter number = (TimeNumberInter) inter;
                    VerticalSide side = number.getSide();

                    if (side == VerticalSide.TOP) {
                        nums.add(inter);
                    } else {
                        dens.add(inter);
                    }
                }
            }
        }
    }

    //-------------//
    // leavesStaff //
    //-------------//
    /**
     * Report whether ink through the candidate glyph strays past the staff outer lines.
     * A time signature is drawn wholly within the staff, so such ink belongs to something
     * else, such as the stem of a chord opening the measure.
     *
     * @param candidate a candidate read at the measure opening
     * @return true if so
     */
    private boolean leavesStaff (Inter candidate)
    {
        final Rectangle box = candidate.getGlyph().getBounds();
        final int x = box.x + (box.width / 2);
        final int stray = scale.getInterlineScale(staff.getSpecificInterline()).toPixels(
                constants.maxStray);

        // The band reaches one row past the stray allowed on either side
        final int top = staff.getFirstLine().yAt(x) - stray - 1;
        final int bottom = staff.getLastLine().yAt(x) + stray + 1;
        final Rectangle band = new Rectangle(box.x, top, box.width, bottom - top + 1);

        final ByteProcessor source = system.getSheet().getPicture().getSource(
                Picture.SourceKey.NO_STAFF);
        final ByteProcessor buf = new ByteProcessor(band.width, band.height);
        buf.copyBits(source, -band.x, -band.y, Blitter.COPY);

        final RunTable runTable = new RunTableFactory(Orientation.VERTICAL).createTable(buf);

        for (Glyph ink : GlyphFactory.buildGlyphs(runTable, band.getLocation())) {
            final Rectangle inkBox = ink.getBounds();

            if (inkBox.intersects(box) && ((inkBox.y == band.y)
                    || ((inkBox.y + inkBox.height) == (band.y + band.height)))) {
                return true;
            }
        }

        return false;
    }

    //-------------//
    // readOpening //
    //-------------//
    /**
     * A time signature opens the measure it governs, so the opening of the staff measure
     * in this stack is read from the image the way a staff header is, whatever the symbols
     * step made of that ink.
     * The first stack of a system opens with the staff header, read by the header step.
     *
     * @param basicColumn the column, whose time set gets the candidates found
     */
    private void readOpening (BasicTimeColumn basicColumn)
    {
        final MeasureStack stack = basicColumn.stack;

        if (stack == system.getFirstStack()) {
            return;
        }

        final StaffHeader.Range range = new StaffHeader.Range();
        range.browseStart = stack.getMeasureAt(staff).getAbscissa(HorizontalSide.LEFT, staff);

        final HeaderTimeBuilder opening = new HeaderTimeBuilder(staff, column, range);
        opening.findCandidates();

        for (List<Inter> found : Arrays.asList(opening.wholes, opening.nums, opening.dens)) {
            for (Inter candidate : found) {
                if (leavesStaff(candidate)) {
                    candidate.remove();
                } else {
                    basicColumn.timeSet.add(candidate);
                }
            }
        }
    }

    //~ Inner Classes ------------------------------------------------------------------------------

    //-----------//
    // Constants //
    //-----------//
    private static class Constants
            extends ConstantSet
    {
        private final Scale.Fraction maxStray = new Scale.Fraction(
                0.5,
                "Maximum distance a time signature ink may stray past the staff outer lines");
    }
}
