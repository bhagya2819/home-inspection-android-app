package api

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"io"
)

// Report-size photos for the Word export, made the same way the phone makes them for the PDF
// (core/report/ReportImages.kt): longest edge 1600 px, JPEG quality 80, EXIF orientation applied.
// Standard library only. One photo at a time: a 12 MP original decodes to ~18 MB.

const (
	reportLongEdge = 1600
	reportQuality  = 80
	// Phone cameras go up to ~200 MP; 50 MP decodes to ~75 MB, inside the container's 256 MB.
	maxReportPixels = 50_000_000
)

// reportJPEG decodes a JPEG original and returns the report-size copy and its pixel size.
func reportJPEG(original []byte) (out []byte, w, h int, err error) {
	// Check the header first: a small file can declare a huge image (decompression bomb).
	cfg, err := jpeg.DecodeConfig(bytes.NewReader(original))
	if err != nil {
		return nil, 0, 0, err
	}
	if cfg.Width <= 0 || cfg.Height <= 0 || int64(cfg.Width)*int64(cfg.Height) > maxReportPixels {
		return nil, 0, 0, fmt.Errorf("image %d×%d is too large", cfg.Width, cfg.Height)
	}
	src, err := jpeg.Decode(bytes.NewReader(original))
	if err != nil {
		return nil, 0, 0, err
	}
	b := src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	scale := min(1.0, float64(reportLongEdge)/float64(max(sw, sh)))
	dw, dh := max(1, int(float64(sw)*scale+0.5)), max(1, int(float64(sh)*scale+0.5))
	img := orient(downscale(src, dw, dh), exifOrientation(original))
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: reportQuality}); err != nil {
		return nil, 0, 0, err
	}
	return buf.Bytes(), img.Bounds().Dx(), img.Bounds().Dy(), nil
}

// downscale averages each destination pixel over its source box (good quality for big
// reductions, unlike nearest-neighbour). The YCbCr fast path avoids an interface call per pixel.
func downscale(src image.Image, dw, dh int) *image.RGBA {
	b := src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	yc, isYCbCr := src.(*image.YCbCr)
	for dy := 0; dy < dh; dy++ {
		y0, y1 := dy*sh/dh, max(dy*sh/dh+1, (dy+1)*sh/dh)
		for dx := 0; dx < dw; dx++ {
			x0, x1 := dx*sw/dw, max(dx*sw/dw+1, (dx+1)*sw/dw)
			var r, g, bl, n uint32
			for sy := y0; sy < y1; sy++ {
				for sx := x0; sx < x1; sx++ {
					px, py := b.Min.X+sx, b.Min.Y+sy
					if isYCbCr {
						yi, ci := yc.YOffset(px, py), yc.COffset(px, py)
						cr, cg, cb := color.YCbCrToRGB(yc.Y[yi], yc.Cb[ci], yc.Cr[ci])
						r, g, bl = r+uint32(cr), g+uint32(cg), bl+uint32(cb)
					} else {
						cr, cg, cb, _ := src.At(px, py).RGBA()
						r, g, bl = r+cr>>8, g+cg>>8, bl+cb>>8
					}
					n++
				}
			}
			i := dst.PixOffset(dx, dy)
			dst.Pix[i], dst.Pix[i+1], dst.Pix[i+2], dst.Pix[i+3] = uint8(r/n), uint8(g/n), uint8(bl/n), 255
		}
	}
	return dst
}

// orient applies an EXIF orientation (1–8) so the photo shows the way the phone was held.
func orient(src *image.RGBA, o int) *image.RGBA {
	if o < 2 || o > 8 {
		return src
	}
	w, h := src.Bounds().Dx(), src.Bounds().Dy()
	dw, dh := w, h
	if o >= 5 { // 5–8 swap width and height
		dw, dh = h, w
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			var nx, ny int
			switch o {
			case 2:
				nx, ny = w-1-x, y
			case 3:
				nx, ny = w-1-x, h-1-y
			case 4:
				nx, ny = x, h-1-y
			case 5:
				nx, ny = y, x
			case 6: // rotate 90° clockwise
				nx, ny = h-1-y, x
			case 7:
				nx, ny = h-1-y, w-1-x
			case 8: // rotate 270° clockwise
				nx, ny = y, w-1-x
			}
			si, di := src.PixOffset(x, y), dst.PixOffset(nx, ny)
			copy(dst.Pix[di:di+4], src.Pix[si:si+4])
		}
	}
	return dst
}

// exifOrientation reads tag 0x0112 from a JPEG's APP1 Exif block; 1 (normal) if absent.
func exifOrientation(jpg []byte) int {
	r := bytes.NewReader(jpg)
	var marker [2]byte
	if _, err := io.ReadFull(r, marker[:]); err != nil || marker != [2]byte{0xFF, 0xD8} {
		return 1
	}
	for {
		if _, err := io.ReadFull(r, marker[:]); err != nil || marker[0] != 0xFF {
			return 1
		}
		if marker[1] == 0xDA || marker[1] == 0xD9 { // start of scan / end: no Exif before the image data
			return 1
		}
		var size uint16
		if err := binary.Read(r, binary.BigEndian, &size); err != nil || size < 2 {
			return 1
		}
		seg := make([]byte, size-2)
		if _, err := io.ReadFull(r, seg); err != nil {
			return 1
		}
		if marker[1] == 0xE1 && len(seg) > 14 && string(seg[:6]) == "Exif\x00\x00" {
			return tiffOrientation(seg[6:])
		}
	}
}

func tiffOrientation(t []byte) int {
	var bo binary.ByteOrder
	switch string(t[:2]) {
	case "II":
		bo = binary.LittleEndian
	case "MM":
		bo = binary.BigEndian
	default:
		return 1
	}
	ifd := int(bo.Uint32(t[4:8]))
	if ifd+2 > len(t) {
		return 1
	}
	n := int(bo.Uint16(t[ifd:]))
	for i := 0; i < n; i++ {
		e := ifd + 2 + i*12
		if e+12 > len(t) {
			return 1
		}
		if bo.Uint16(t[e:]) == 0x0112 {
			return int(bo.Uint16(t[e+8:]))
		}
	}
	return 1
}
