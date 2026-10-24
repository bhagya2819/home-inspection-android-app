package api

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"time"
)

// A zip archive laid out entirely from known sizes and CRC-32s, so its bytes are deterministic:
// the same export always produces the same file. That gives an exact Content-Length up front and
// lets http.ServeContent answer Range requests, so a 2 GB download on mobile data can resume.
//
// Store method only (JPEG and PDF are already compressed; deflating them wastes server CPU for
// ~1 % gain). No data descriptors: sizes and CRC sit in each local header, which every reader —
// including Java's streaming ZipInputStream used by the app's verifier — understands. No Zip64:
// exports are capped well below 4 GiB and 65,535 entries.

type zipEntry struct {
	name string
	size int64
	crc  uint32
	data []byte // in-memory content (manifest.csv, data.json), or
	file string // path of an object file under DATA_DIR
}

type zipSegment struct {
	off, n int64
	data   []byte // header bytes or in-memory content
	file   string
}

// zipLayout is an io.ReadSeeker over the virtual archive.
type zipLayout struct {
	segs []zipSegment
	size int64
	pos  int64

	open     *os.File
	openName string
}

const (
	zipMaxBytes   = 3_500_000_000 // stays under the 4 GiB limit of plain (non-Zip64) offsets
	zipMaxEntries = 60_000
)

func newZipLayout(entries []zipEntry, modified time.Time) (*zipLayout, error) {
	if len(entries) > zipMaxEntries {
		return nil, fmt.Errorf("too many entries: %d", len(entries))
	}
	dosTime, dosDate := dosDateTime(modified)
	z := &zipLayout{}
	var off int64
	add := func(s zipSegment) {
		s.off = off
		off += s.n
		z.segs = append(z.segs, s)
	}
	offsets := make([]int64, len(entries))
	for i, e := range entries {
		offsets[i] = off
		h := make([]byte, 30+len(e.name))
		binary.LittleEndian.PutUint32(h[0:], 0x04034b50)
		binary.LittleEndian.PutUint16(h[4:], 20) // version needed: 2.0
		binary.LittleEndian.PutUint16(h[6:], 0)  // flags
		binary.LittleEndian.PutUint16(h[8:], 0)  // method: store
		binary.LittleEndian.PutUint16(h[10:], dosTime)
		binary.LittleEndian.PutUint16(h[12:], dosDate)
		binary.LittleEndian.PutUint32(h[14:], e.crc)
		binary.LittleEndian.PutUint32(h[18:], uint32(e.size))
		binary.LittleEndian.PutUint32(h[22:], uint32(e.size))
		binary.LittleEndian.PutUint16(h[26:], uint16(len(e.name)))
		binary.LittleEndian.PutUint16(h[28:], 0)
		copy(h[30:], e.name)
		add(zipSegment{n: int64(len(h)), data: h})
		if e.data != nil {
			add(zipSegment{n: int64(len(e.data)), data: e.data})
		} else if e.size > 0 {
			add(zipSegment{n: e.size, file: e.file})
		}
	}
	cdStart := off
	var cd []byte
	for i, e := range entries {
		h := make([]byte, 46+len(e.name))
		binary.LittleEndian.PutUint32(h[0:], 0x02014b50)
		binary.LittleEndian.PutUint16(h[4:], 20)
		binary.LittleEndian.PutUint16(h[6:], 20)
		binary.LittleEndian.PutUint16(h[10:], 0)
		binary.LittleEndian.PutUint16(h[12:], dosTime)
		binary.LittleEndian.PutUint16(h[14:], dosDate)
		binary.LittleEndian.PutUint32(h[16:], e.crc)
		binary.LittleEndian.PutUint32(h[20:], uint32(e.size))
		binary.LittleEndian.PutUint32(h[24:], uint32(e.size))
		binary.LittleEndian.PutUint16(h[28:], uint16(len(e.name)))
		binary.LittleEndian.PutUint32(h[42:], uint32(offsets[i]))
		copy(h[46:], e.name)
		cd = append(cd, h...)
	}
	end := make([]byte, 22)
	binary.LittleEndian.PutUint32(end[0:], 0x06054b50)
	binary.LittleEndian.PutUint16(end[8:], uint16(len(entries)))
	binary.LittleEndian.PutUint16(end[10:], uint16(len(entries)))
	binary.LittleEndian.PutUint32(end[12:], uint32(len(cd)))
	binary.LittleEndian.PutUint32(end[16:], uint32(cdStart))
	add(zipSegment{n: int64(len(cd) + len(end)), data: append(cd, end...)})
	z.size = off
	if z.size > zipMaxBytes {
		return nil, fmt.Errorf("archive too large: %d bytes", z.size)
	}
	return z, nil
}

func dosDateTime(t time.Time) (uint16, uint16) {
	t = t.UTC()
	if t.Year() < 1980 {
		t = time.Date(1980, 1, 1, 0, 0, 0, 0, time.UTC)
	}
	return uint16(t.Hour()<<11 | t.Minute()<<5 | t.Second()/2),
		uint16((t.Year()-1980)<<9 | int(t.Month())<<5 | t.Day())
}

func (z *zipLayout) Size() int64 { return z.size }

func (z *zipLayout) Seek(offset int64, whence int) (int64, error) {
	var abs int64
	switch whence {
	case io.SeekStart:
		abs = offset
	case io.SeekCurrent:
		abs = z.pos + offset
	case io.SeekEnd:
		abs = z.size + offset
	default:
		return 0, errors.New("zip: bad whence")
	}
	if abs < 0 {
		return 0, errors.New("zip: negative position")
	}
	z.pos = abs
	return abs, nil
}

func (z *zipLayout) Read(p []byte) (int, error) {
	if z.pos >= z.size {
		return 0, io.EOF
	}
	i := sort.Search(len(z.segs), func(i int) bool { return z.segs[i].off+z.segs[i].n > z.pos })
	s := z.segs[i]
	within := z.pos - s.off
	want := min(int64(len(p)), s.n-within)
	var n int
	if s.data != nil {
		n = copy(p[:want], s.data[within:])
	} else {
		f, err := z.fileFor(s.file)
		if err != nil {
			return 0, err
		}
		n, err = f.ReadAt(p[:want], within)
		if n < int(want) {
			// The object is shorter than recorded: never pad, fail the transfer instead.
			if err == nil || err == io.EOF {
				err = io.ErrUnexpectedEOF
			}
			z.pos += int64(n)
			return n, err
		}
	}
	z.pos += int64(n)
	return n, nil
}

func (z *zipLayout) fileFor(path string) (*os.File, error) {
	if z.open != nil && z.openName == path {
		return z.open, nil
	}
	z.Close()
	f, err := os.Open(filepath.Clean(path))
	if err != nil {
		return nil, err
	}
	z.open, z.openName = f, path
	return f, nil
}

func (z *zipLayout) Close() error {
	if z.open == nil {
		return nil
	}
	err := z.open.Close()
	z.open, z.openName = nil, ""
	return err
}
