# SPDX-License-Identifier: GPL-3.0-or-later
# OpenClip M1 engine Makefile (host Linux build).
CXX ?= g++
CXXFLAGS ?= -std=c++17 -O2 -Wall -Wextra -Wpedantic
INCLUDES = -Iinclude -Ithird_party -Isrc/io

SRCS = src/raster/surface.cpp \
       src/raster/canvas.cpp \
       src/blend/blend.cpp \
       src/brush/brush.cpp \
       src/stroke/stroke.cpp \
       src/undo/undo.cpp \
       src/io/minizip.cpp \
       src/io/ora.cpp \
       src/io/psd.cpp \
       src/io/document.cpp \
       src/filters/filters.cpp \
       src/raster/vector.cpp

TEST_SRCS = tests/main.cpp \
            tests/test_surface.cpp \
            tests/test_blend.cpp \
            tests/test_brush.cpp \
            tests/test_stroke.cpp \
            tests/test_undo.cpp \
            tests/test_ora.cpp \
            tests/test_filters.cpp \
            tests/test_document.cpp \
            tests/test_layers.cpp \
            tests/test_vector.cpp \
            tests/test_adjust.cpp

OBJDIR = obj
LIB = $(OBJDIR)/libopenclip.a
TESTBIN = $(OBJDIR)/openclip_tests

OBJS = $(SRCS:%.cpp=$(OBJDIR)/%.o)
TEST_OBJS = $(TEST_SRCS:%.cpp=$(OBJDIR)/%.o)

all: $(LIB) $(TESTBIN)

$(OBJDIR)/%.o: %.cpp
	@mkdir -p $(dir $@)
	$(CXX) $(CXXFLAGS) $(INCLUDES) -c $< -o $@

$(LIB): $(OBJS)
	ar rcs $@ $(OBJS)

$(TESTBIN): $(LIB) $(TEST_OBJS)
	$(CXX) $(CXXFLAGS) $(TEST_OBJS) -L$(OBJDIR) -lopenclip -lz -o $@

test: $(TESTBIN)
	./$(TESTBIN)

clean:
	rm -rf $(OBJDIR)

.PHONY: all test clean
